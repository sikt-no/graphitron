package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.session.SessionHooks;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Emits {@code GraphitronConnectionInstrumentation}, the graphql-java
 * {@link graphql.execution.instrumentation.Instrumentation} that owns the per-operation connection
 * lifecycle, into the consumer's {@code <outputPackage>.schema} package. Wired by the emitted engine
 * assembly ({@code GraphitronRuntime.newGraphQL(schema)}), so consumers register nothing.
 *
 * <p>Emitted (not shipped as a graphitron artifact); bodies depend only on graphql-java, jOOQ, and
 * the JDK. Valid Java 17.
 *
 * <h2>Per-operation sequence ({@code beginExecuteOperation})</h2>
 * <ol>
 *   <li>Read each mount payload contextArgument off the {@code graphQLContext} (the name-keyed
 *       entries the {@code Graphitron.newOwnedExecutionInput(...)} factory writes, the same
 *       per-request extraction {@code @service} call sites use).</li>
 *   <li>Publish the per-operation {@code TenantConnections} carrier under its own class key, on
 *       both topologies. Acquisition is lazy on every path: fetchers resolve contexts through
 *       the carrier ({@code getDslContext(env)} / {@code dslFor} / {@code dslDefault}), which
 *       pins and mounts one connection per key on first demand, so an operation that touches no
 *       database never pins and never mounts. Nothing is published under the typed
 *       {@code DSLContext.class} key: that key belongs to the escape-hatch factory alone, so the
 *       accessor distinguishes the modes structurally.</li>
 *   <li>No outer transaction is opened. <b>Query operations</b> run in autocommit, with no
 *       read-only enforcement. <b>Mutation operations</b> let each
 *       field's shipped {@code dsl.transactionResult(...)} be the per-field writable boundary through
 *       the provider: under {@code COMMIT} each field's transaction commits or rolls back
 *       independently; under {@code ROLLBACK_ONLY} (the dev-execution mode) the provider's deferred
 *       observe-then-discard topology savepoint-scopes each field inside one operation transaction
 *       that release discards.</li>
 *   <li>On completion (success, error, cancellation) release every pinned connection
 *       ({@code releaseAll()}: unmount, then return-or-evict). Release is idempotent.</li>
 * </ol>
 *
 * <h2>Incremental delivery is rejected</h2>
 * Connection-per-operation release closes the pinned connections at completion; a deferred fetcher
 * running afterwards would use a closed connection. The emitted class therefore rejects
 * {@code @defer}/{@code @stream} outright ({@code hasIncrementalSupport()} fails fast) rather than
 * let incremental delivery corrupt the connection lifetime.
 */
public final class GraphitronConnectionInstrumentationGenerator {

    public static final String CLASS_NAME = "GraphitronConnectionInstrumentation";

    private static final ClassName INSTRUMENTATION = ClassName.get("graphql.execution.instrumentation", "Instrumentation");
    private static final ClassName INSTRUMENTATION_STATE = ClassName.get("graphql.execution.instrumentation", "InstrumentationState");
    private static final ClassName INSTRUMENTATION_CONTEXT = ClassName.get("graphql.execution.instrumentation", "InstrumentationContext");
    private static final ClassName SIMPLE_INSTRUMENTATION_CONTEXT = ClassName.get("graphql.execution.instrumentation", "SimpleInstrumentationContext");
    private static final ClassName CREATE_STATE_PARAMS = ClassName.get("graphql.execution.instrumentation.parameters", "InstrumentationCreateStateParameters");
    private static final ClassName EXECUTE_OPERATION_PARAMS = ClassName.get("graphql.execution.instrumentation.parameters", "InstrumentationExecuteOperationParameters");
    private static final ClassName EXECUTION_RESULT = ClassName.get("graphql", "ExecutionResult");
    private static final ClassName GRAPHQL_CONTEXT = ClassName.get("graphql", "GraphQLContext");
    private static final ClassName EXECUTION_CONTEXT = ClassName.get("graphql.execution", "ExecutionContext");

    private GraphitronConnectionInstrumentationGenerator() {}

    /**
     * @param outputPackage the consumer's root output package; the class is emitted into
     *                      {@code outputPackage + ".schema"} (beside {@code GraphitronRuntime})
     */
    public static List<TypeSpec> generate(String outputPackage) {
        return generate(outputPackage, false, SessionHooks.NotConfigured.INSTANCE);
    }

    /**
     * Canonical form. {@code multiTenant} is true when {@code <tenantColumn>} is configured (the
     * carrier then additionally carries the fan-out machinery); acquisition is lazy on both
     * topologies, so this instrumentation publishes the per-operation carrier and pins nothing
     * itself. {@code sessionHooks} supplies the mount's payload contextArguments, read here off
     * the {@code graphQLContext} and retained by the carrier for the life of the request.
     */
    public static List<TypeSpec> generate(String outputPackage, boolean multiTenant, SessionHooks sessionHooks) {
        String schemaPackage = outputPackage + ".schema";
        var self = ClassName.get(schemaPackage, CLASS_NAME);
        var runtime = ClassName.get(schemaPackage, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        var tenantConnections = ClassName.get(schemaPackage, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        var provider = ClassName.get(schemaPackage, GraphitronTransactionProviderGenerator.CLASS_NAME);
        var commitPolicy = provider.nestedClass(GraphitronTransactionProviderGenerator.COMMIT_POLICY_ENUM_NAME);
        var state = self.nestedClass("State");
        return List.of(instrumentation(self, runtime, tenantConnections, commitPolicy, state, multiTenant,
            sessionHooks));
    }

    private static TypeSpec instrumentation(
            ClassName self, ClassName runtime, ClassName tenantConnections,
            ClassName commitPolicy, ClassName state, boolean multiTenant, SessionHooks sessionHooks) {

        var runtimeField = FieldSpec.builder(runtime, "runtime", Modifier.PRIVATE, Modifier.FINAL).build();
        var policyField = FieldSpec.builder(commitPolicy, "commitPolicy", Modifier.PRIVATE, Modifier.FINAL).build();

        var primaryConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(runtime, "runtime")
            .addStatement("this(runtime, $T.COMMIT)", commitPolicy)
            .addJavadoc("Builds the instrumentation over {@code runtime} with the default {@code COMMIT}\n"
                + "policy (successful transactions persist).\n")
            .build();

        var policyConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(runtime, "runtime")
            .addParameter(commitPolicy, "commitPolicy")
            .addStatement("this.runtime = runtime")
            .addStatement("this.commitPolicy = commitPolicy")
            .addJavadoc("Builds the instrumentation over {@code runtime} with an explicit commit policy;\n"
                + "{@code ROLLBACK_ONLY} is the rollback-everything dev mode (see its enum constant\n"
                + "for the deferred observe-then-discard topology).\n")
            .build();

        var createState = MethodSpec.methodBuilder("createState")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(INSTRUMENTATION_STATE)
            .addParameter(CREATE_STATE_PARAMS, "parameters")
            .addStatement("return new $T()", state)
            .addJavadoc("Per-request state carrier holding the connection carrier for\n"
                + "{@link #beginExecuteOperation} to release at completion.\n")
            .build();

        var resultContext = ParameterizedTypeName.get(INSTRUMENTATION_CONTEXT, EXECUTION_RESULT);

        var payload = payloadParams(sessionHooks);
        var beginExecuteOperationBuilder = MethodSpec.methodBuilder("beginExecuteOperation")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(resultContext)
            .addParameter(EXECUTE_OPERATION_PARAMS, "parameters")
            .addParameter(INSTRUMENTATION_STATE, "rawState")
            .addStatement("$T state = ($T) rawState", state, state)
            .addStatement("$T executionContext = parameters.getExecutionContext()", EXECUTION_CONTEXT)
            .addStatement("$T graphQLContext = executionContext.getGraphQLContext()", GRAPHQL_CONTEXT)
            .addCode("\n")
            .beginControlFlow("if (executionContext.hasIncrementalSupport())")
            .addComment("@defer/@stream would run fetchers after release closes the pinned connections.")
            .addStatement("throw new $T($S)", IllegalStateException.class,
                "Incremental delivery (@defer/@stream) is not supported on the Graphitron "
                + "owned-connection path; it is a named follow-on")
            .endControlFlow()
            .addCode("\n");
        if (!payload.isEmpty()) {
            beginExecuteOperationBuilder.addComment(
                "The mount's payload contextArguments, written name-keyed by newOwnedExecutionInput;");
            beginExecuteOperationBuilder.addComment(
                "the carrier retains them for the request, since any fetcher may trigger a mount.");
            for (var p : payload) {
                beginExecuteOperationBuilder.addStatement("$T $L = graphQLContext.get($S)",
                    p.javaType(), p.name(), p.name());
            }
        }
        var carrierArgs = new StringBuilder("runtime, commitPolicy");
        for (var p : payload) {
            carrierArgs.append(", ").append(p.name());
        }
        var beginExecuteOperation = beginExecuteOperationBuilder
            .addComment("Publish the per-operation carrier; acquisition is lazy on every path. Fetchers")
            .addComment("resolve contexts through the carrier (getDslContext / dslFor / dslDefault), which")
            .addComment("pins and mounts one connection per key on first demand; an operation that touches")
            .addComment("no database never pins. Nothing is published under DSLContext.class: the typed key")
            .addComment("belongs to the escape-hatch factory alone.")
            .addStatement("$T carrier = new $T($L)", tenantConnections, tenantConnections, carrierArgs.toString())
            .addStatement("state.carrier = carrier")
            .addStatement("graphQLContext.put($T.class, carrier)", tenantConnections)
            .addCode("\n")
            .addComment("Release every pinned connection on every completion path (success, error,")
            .addComment("cancellation). releaseAll() is idempotent and one entry's unmount failure never")
            .addComment("orphans another's connection.")
            .addStatement("return $T.whenCompleted((result, throwable) -> state.carrier.releaseAll())",
                SIMPLE_INSTRUMENTATION_CONTEXT)
            .addJavadoc("Publishes the per-operation connection carrier for the owned-connection path and\n"
                + "releases every pinned connection at completion; acquisition is lazy and per key, with\n"
                + "single-tenant as the one-key case. See the class javadoc for the full sequence.\n")
            .build();

        var stateType = TypeSpec.classBuilder("State")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(INSTRUMENTATION_STATE)
            .addField(FieldSpec.builder(tenantConnections, "carrier", Modifier.PRIVATE).build())
            .addJavadoc("Per-request instrumentation state: the connection carrier to release at\n"
                + "completion.\n")
            .build();

        return TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(INSTRUMENTATION)
            .addJavadoc("graphql-java {@link $T} that owns the per-operation connection lifecycle for the\n"
                + "Graphitron owned-connection path. Wired by {@code GraphitronRuntime.newGraphQL(schema)};\n"
                + "consumers register nothing. See {@code GraphitronConnectionInstrumentationGenerator}.\n",
                INSTRUMENTATION)
            .addField(runtimeField)
            .addField(policyField)
            .addMethod(primaryConstructor)
            .addMethod(policyConstructor)
            .addMethod(createState)
            .addMethod(beginExecuteOperation)
            .addType(stateType)
            .build();
    }

    /** The mount's payload parameters as typed params, empty when nothing is configured. */
    private static List<no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed> payloadParams(SessionHooks sessionHooks) {
        return sessionHooks.mountRef()
            .map(m -> m.params().stream()
                .filter(p -> p instanceof no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed typed
                    && typed.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.Context)
                .map(p -> (no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed) p)
                .toList())
            .orElse(List.of());
    }
}
