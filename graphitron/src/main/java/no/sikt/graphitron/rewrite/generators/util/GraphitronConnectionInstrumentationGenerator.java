package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * R429 slice 2 — emits {@code GraphitronConnectionInstrumentation}, the graphql-java
 * {@link graphql.execution.instrumentation.Instrumentation} that owns the per-operation connection
 * lifecycle, into the consumer's {@code <outputPackage>.schema} package. Wired by the emitted engine
 * assembly ({@code GraphitronRuntime.newGraphQL(schema)}), so consumers register nothing.
 *
 * <p>Emitted (not shipped as a graphitron artifact); bodies depend only on graphql-java, jOOQ, and
 * the JDK. Valid Java 17.
 *
 * <h2>The seam this closes (R190 revision)</h2>
 * On the escape-hatch path {@code Graphitron.newExecutionInput(dsl, ...)} publishes the per-request
 * {@code DSLContext} under the {@code DSLContext.class} {@code graphQLContext} key at factory time. On
 * the owned-connection path the <em>producer of that key moves here</em>: at operation start this
 * instrumentation pins a connection, mounts identity, binds a {@code DSLContext} to it, and publishes
 * it under the very same key. Every consumer of the key ({@code getDslContext(env)}, every generated
 * fetcher) is untouched.
 *
 * <h2>Per-operation sequence ({@link #beginExecuteOperation})</h2>
 * <ol>
 *   <li>Read the opaque claims payload from the {@code graphQLContext} under {@link #CLAIMS_KEY} (the
 *       one key slice 5's {@code Graphitron.newOwnedExecutionInput(claims, ...)} factory writes; shared as a
 *       named constant so read and write cannot drift).</li>
 *   <li>{@code runtime.acquire(claims)} pins one connection and runs the connect hook; a throwing
 *       connect fails closed here, before any SQL, surfaced as a request error.</li>
 *   <li>Bind a {@code DSLContext} to the pinned connection through
 *       {@link GraphitronTransactionProviderGenerator the custom TransactionProvider} and publish it
 *       under {@code DSLContext.class}.</li>
 *   <li>No outer transaction is opened. <b>Query operations</b> run in autocommit (R429 drops blanket
 *       read-only enforcement; the targeted successor is R460). <b>Mutation operations</b> let each
 *       field's shipped {@code dsl.transactionResult(...)} be the per-field writable boundary through
 *       the provider, committing or rolling back independently under the commit policy.</li>
 *   <li>On completion (success, error, cancellation) release the pinned connection (disconnect hook,
 *       then return-or-evict). Release is idempotent.</li>
 * </ol>
 *
 * <h2>@defer stays off (spec V0 stance)</h2>
 * Connection-per-operation release closes the pinned connection at completion; a deferred fetcher
 * running afterwards would use a closed connection. So this rejects incremental delivery outright
 * rather than let it corrupt the lifetime: {@code hasIncrementalSupport()} is a build-off invariant,
 * and enabling {@code @defer} under owned connections is a named follow-on.
 */
public final class GraphitronConnectionInstrumentationGenerator {

    public static final String CLASS_NAME = "GraphitronConnectionInstrumentation";
    public static final String CLAIMS_KEY_FIELD = "CLAIMS_KEY";
    /** The literal value of the emitted {@code CLAIMS_KEY} constant (also referenced by slice 5's factory). */
    public static final String CLAIMS_KEY_VALUE = "no.sikt.graphitron.request.claims";

    private static final ClassName INSTRUMENTATION = ClassName.get("graphql.execution.instrumentation", "Instrumentation");
    private static final ClassName INSTRUMENTATION_STATE = ClassName.get("graphql.execution.instrumentation", "InstrumentationState");
    private static final ClassName INSTRUMENTATION_CONTEXT = ClassName.get("graphql.execution.instrumentation", "InstrumentationContext");
    private static final ClassName SIMPLE_INSTRUMENTATION_CONTEXT = ClassName.get("graphql.execution.instrumentation", "SimpleInstrumentationContext");
    private static final ClassName CREATE_STATE_PARAMS = ClassName.get("graphql.execution.instrumentation.parameters", "InstrumentationCreateStateParameters");
    private static final ClassName EXECUTE_OPERATION_PARAMS = ClassName.get("graphql.execution.instrumentation.parameters", "InstrumentationExecuteOperationParameters");
    private static final ClassName EXECUTION_RESULT = ClassName.get("graphql", "ExecutionResult");
    private static final ClassName GRAPHQL_CONTEXT = ClassName.get("graphql", "GraphQLContext");
    private static final ClassName EXECUTION_CONTEXT = ClassName.get("graphql.execution", "ExecutionContext");

    private static final ClassName CONNECTION = ClassName.get("java.sql", "Connection");
    private static final ClassName SQL_EXCEPTION = ClassName.get("java.sql", "SQLException");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");

    private GraphitronConnectionInstrumentationGenerator() {}

    /**
     * @param outputPackage the consumer's root output package; the class is emitted into
     *                      {@code outputPackage + ".schema"} (beside {@code GraphitronRuntime})
     */
    public static List<TypeSpec> generate(String outputPackage) {
        String schemaPackage = outputPackage + ".schema";
        var self = ClassName.get(schemaPackage, CLASS_NAME);
        var runtime = ClassName.get(schemaPackage, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        var pinnedConnection = ClassName.get(schemaPackage, ConnectionRuntimeClassGenerator.PINNED_CONNECTION_CLASS_NAME);
        var provider = ClassName.get(schemaPackage, GraphitronTransactionProviderGenerator.CLASS_NAME);
        var commitPolicy = provider.nestedClass(GraphitronTransactionProviderGenerator.COMMIT_POLICY_ENUM_NAME);
        var state = self.nestedClass("State");
        return List.of(instrumentation(self, runtime, pinnedConnection, provider, commitPolicy, state));
    }

    private static TypeSpec instrumentation(
            ClassName self, ClassName runtime, ClassName pinnedConnection,
            ClassName provider, ClassName commitPolicy, ClassName state) {

        var claimsKey = FieldSpec.builder(String.class, CLAIMS_KEY_FIELD, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$S", CLAIMS_KEY_VALUE)
            .addJavadoc("The {@code graphQLContext} key the opaque per-request claims payload is published\n"
                + "under. Read here at acquisition; written by the\n"
                + "{@code Graphitron.newOwnedExecutionInput(claims, ...)} factory. One constant so the two\n"
                + "sites cannot drift on the key string.\n")
            .build();

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
                + "{@code ROLLBACK_ONLY} is R428's rollback-everything dev mode.\n")
            .build();

        var createState = MethodSpec.methodBuilder("createState")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(INSTRUMENTATION_STATE)
            .addParameter(CREATE_STATE_PARAMS, "parameters")
            .addStatement("return new $T()", state)
            .addJavadoc("Per-request state carrier holding the pinned connection for\n"
                + "{@link #beginExecuteOperation} to release at completion.\n")
            .build();

        var resultContext = ParameterizedTypeName.get(INSTRUMENTATION_CONTEXT, EXECUTION_RESULT);

        var beginExecuteOperation = MethodSpec.methodBuilder("beginExecuteOperation")
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
            .addComment("@defer/@stream would run fetchers after release closes the pinned connection.")
            .addStatement("throw new $T($S)", IllegalStateException.class,
                "Incremental delivery (@defer/@stream) is not supported on the Graphitron "
                + "owned-connection path; it is a named follow-on")
            .endControlFlow()
            .addCode("\n")
            .addStatement("$T claims = graphQLContext.get($L)", String.class, CLAIMS_KEY_FIELD)
            .addStatement("$T pinned", pinnedConnection)
            .beginControlFlow("try")
            .addStatement("pinned = runtime.acquire(claims)")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addComment("A throwing connect hook (fail-closed) propagates as an unchecked cause already;")
            .addComment("only the getConnection() SQLException is checked. Either way: request error, no SQL ran.")
            .addStatement("throw new $T($S, e)", RuntimeException.class, "Could not acquire a database connection")
            .endControlFlow()
            .addStatement("state.pinned = pinned")
            .addStatement("$T connection = pinned.connection()", CONNECTION)
            .addCode("\n")
            .addComment("Bind a DSLContext to the pinned connection, then swap in the transaction provider (the")
            .addComment("one seam) on its live configuration, and publish it under the same key getDslContext(env)")
            .addComment("reads. DSL.using(connection, dialect) wraps the connection in jOOQ's own single-connection")
            .addComment("provider whose release is a no-op, so jOOQ never closes it; the runtime owns close/evict.")
            .addComment("The settle callback re-fires unconfirmed session hooks after each per-field settle, so")
            .addComment("post-commit read-back projections and later mutation fields see remounted identity.")
            .addStatement("$T dsl = $T.using(connection, runtime.dialect())", DSL_CONTEXT, DSL)
            .addStatement("dsl.configuration().set(new $T(connection, commitPolicy, pinned::afterSettle))", provider)
            .addStatement("graphQLContext.put($T.class, dsl)", DSL_CONTEXT)
            .addCode("\n")
            .addComment("Release the pinned connection on every completion path (success, error, cancellation).")
            .addComment("Queries run in autocommit (no outer transaction); each mutation field owns its own")
            .addComment("per-field transaction through the provider on the published DSLContext. release() is")
            .addComment("idempotent and evicts on disconnect failure.")
            .addStatement("return $T.whenCompleted((result, throwable) -> state.pinned.release())",
                SIMPLE_INSTRUMENTATION_CONTEXT)
            .addJavadoc("Pins the connection, mounts identity, and publishes its {@code DSLContext} under the\n"
                + "key {@code getDslContext(env)} reads, then releases on completion. See the class javadoc\n"
                + "for the full sequence.\n")
            .build();

        var stateType = TypeSpec.classBuilder("State")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(INSTRUMENTATION_STATE)
            .addField(FieldSpec.builder(pinnedConnection, "pinned", Modifier.PRIVATE).build())
            .addJavadoc("Per-request instrumentation state: the pinned connection to release at completion.\n")
            .build();

        return TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(INSTRUMENTATION)
            .addJavadoc("graphql-java {@link $T} that owns the per-operation connection lifecycle for the\n"
                + "Graphitron owned-connection path. Wired by {@code GraphitronRuntime.newGraphQL(schema)};\n"
                + "consumers register nothing. See {@code GraphitronConnectionInstrumentationGenerator}.\n",
                INSTRUMENTATION)
            .addField(claimsKey)
            .addField(runtimeField)
            .addField(policyField)
            .addMethod(primaryConstructor)
            .addMethod(policyConstructor)
            .addMethod(createState)
            .addMethod(beginExecuteOperation)
            .addType(stateType)
            .build();
    }
}
