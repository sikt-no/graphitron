package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator;
import no.sikt.graphitron.emit.SessionIdentityRows;

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
 *   <li>In a {@code <tenantColumn>} build, read the {@code RequestTenants} value the factory
 *       wrote under {@code TenantConnections.TENANTS_KEY}, failing the operation before any
 *       fetcher runs when it is absent (an {@code ExecutionInput} built without the generated
 *       factory), since the set has no "unrestricted" meaning, and when it names a default
 *       tenant this runtime has no {@code DataSource} for.</li>
 *   <li>Read the request's {@code SessionState} off the {@code graphQLContext} (the key
 *       entries the {@code Graphitron.newOwnedExecutionInput(...)} factory writes, the same
 *       per-request extraction {@code @service} call sites use).</li>
 *   <li>Publish the runtime's {@code FetcherErrorLog} and {@code FetcherErrorResponse}, each when
 *       one is set, under its class key, where {@code ErrorRouter} reads them for every fetcher
 *       exception.</li>
 *   <li>Publish the per-operation {@code TenantConnections} carrier under its own class key, on
 *       both topologies. Acquisition is lazy on every path: fetchers resolve contexts through
 *       the carrier ({@code getDslContext(env)} / {@code dslFor} / {@code dslDefault} /
 *       {@code dslGlobal}), which
 *       pins and mounts one connection per key on first demand, so an operation that touches no
 *       database never pins and never mounts. Nothing is published under the typed
 *       {@code DSLContext.class} key: that key belongs to the escape-hatch factory alone, so the
 *       accessor distinguishes the modes structurally.</li>
 *   <li><b>Query operations</b> run in autocommit, with no read-only enforcement.
 *       <b>Mutation operations</b> are refused unless the engine's mutation strategy is
 *       graphql-java's serial one ({@code AsyncSerialExecutionStrategy} or a subclass), since each
 *       top-level field settles when the next one starts.</li>
 *   <li>On completion (success, error, cancellation) settle the last mutation field, then release
 *       every pinned connection ({@code releaseAll()}: unmount, then return-or-evict), in that
 *       order, in the one callback of the context this method returns. Release is idempotent.</li>
 * </ol>
 *
 * <h2>One transaction per top-level mutation field</h2>
 * {@code beginFieldExecution} settles the previous top-level mutation field and begins the next on
 * the carrier ({@code TenantConnections.beginField}); the carrier opens the field's transaction on
 * each connection the field first acquires and every jOOQ transaction inside it joins it, so the
 * field's writes and its payload's reads, batched sub-fields included, are one transaction per
 * connection. Settling here rather than in the previous field's own
 * {@code beginFieldExecution} completion matters: graphql-java completes that context before the
 * field's sub-selections are done. The last field settles in the operation callback, ahead of
 * release by construction; the strategy's own completion is a sibling callback on the same
 * future, which {@code CompletableFuture} may run after release. A field fails, and rolls back,
 * when an error lies under its path or {@code ErrorRouter} marked it (an exception redacted,
 * surfaced or claimed into an {@code @error} payload). Under {@code ROLLBACK_ONLY} (the
 * dev-execution mode) each field's scope is a savepoint in one operation transaction that release
 * discards.
 *
 * <p>A field whose transaction fails to settle after the field resolved (a failed commit, a
 * PostgreSQL transaction aborted by a statement the field's code caught, a timed-out tenant) is
 * reported by {@code instrumentExecutionResult}, which runs after the operation callback: its
 * value becomes {@code null}, with null propagation for a non-null field, and its redacted error,
 * logged under a reference through the consumer's {@code FetcherErrorLog}, is added at its path.
 * A rejected session mount still takes precedence and is thrown in place of the result.
 *
 * <p>A field that rolled back because of an error under its path has its value dropped too, with
 * no error added, and a field whose consumer-opened nested transaction failed gets the carrier's
 * nested-failure error beside whatever else it reported. An {@code @error} channel's payload
 * stays, since it describes the failure.
 *
 * <h2>A rejected mount rejects the request ({@code instrumentExecutionResult})</h2>
 * When {@code <sessionState>} configures a mount and it threw during the operation, the carrier
 * remembers the first {@code SessionMountException}, and this override throws it in place of the
 * result, so it escapes {@code GraphQL.execute}. Emitted only with a mount.
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
    private static final ClassName EXECUTION_PARAMS = ClassName.get("graphql.execution.instrumentation.parameters", "InstrumentationExecutionParameters");
    private static final ClassName COMPLETABLE_FUTURE = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName FIELD_PARAMS = ClassName.get("graphql.execution.instrumentation.parameters", "InstrumentationFieldParameters");
    private static final ClassName FIELD_FETCH_PARAMS = ClassName.get("graphql.execution.instrumentation.parameters", "InstrumentationFieldFetchParameters");
    private static final ClassName FIELD_FETCHING_CONTEXT = ClassName.get("graphql.execution.instrumentation", "FieldFetchingInstrumentationContext");
    private static final ClassName OPERATION = ClassName.get("graphql.language", "OperationDefinition").nestedClass("Operation");
    private static final ClassName SERIAL_STRATEGY = ClassName.get("graphql.execution", "AsyncSerialExecutionStrategy");
    private static final ClassName RESULT_PATH = ClassName.get("graphql.execution", "ResultPath");
    private static final ClassName EXECUTION_STEP_INFO = ClassName.get("graphql.execution", "ExecutionStepInfo");
    private static final ClassName DATA_FETCHING_ENVIRONMENT = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName GRAPHQL_ERROR = ClassName.get("graphql", "GraphQLError");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");

    private GraphitronConnectionInstrumentationGenerator() {}

    /**
     * @param outputPackage the consumer's root output package; the class is emitted into
     *                      {@code outputPackage + ".schema"} (beside {@code GraphitronRuntime})
     */
    public static List<TypeSpec> generate(String outputPackage) {
        return generate(outputPackage, null, SessionIdentityRows.empty());
    }

    /**
     * Canonical form. {@code tenantKeyType} is the tenant column's Java type when
     * {@code <tenantColumn>} is configured, {@code null} otherwise (the carrier then additionally
     * carries the request tenant set and the fan-out machinery); acquisition is lazy on both
     * topologies, so this instrumentation publishes the per-operation carrier and pins nothing
     * itself. {@code sessionHooks} says whether the graph supplies session identity, whose state is read here off
     * the {@code graphQLContext} and retained by the carrier for the life of the request.
     */
    public static List<TypeSpec> generate(String outputPackage, TypeName tenantKeyType, SessionIdentityRows sessionHooks) {
        TypeName tenantKey = tenantKeyType == null ? null
            : (tenantKeyType.isPrimitive() ? tenantKeyType.box() : tenantKeyType);
        String schemaPackage = outputPackage + ".schema";
        var self = ClassName.get(schemaPackage, CLASS_NAME);
        var runtime = ClassName.get(schemaPackage, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        var tenantConnections = ClassName.get(schemaPackage, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        var requestTenants = ClassName.get(schemaPackage, ConnectionRuntimeClassGenerator.REQUEST_TENANTS_CLASS_NAME);
        var fetcherErrorLog = ErrorRouterClassGenerator.fetcherErrorLogClass(outputPackage);
        var fetcherErrorResponse = ErrorRouterClassGenerator.fetcherErrorResponseClass(outputPackage);
        var provider = ClassName.get(schemaPackage, GraphitronTransactionProviderGenerator.CLASS_NAME);
        var commitPolicy = provider.nestedClass(GraphitronTransactionProviderGenerator.COMMIT_POLICY_ENUM_NAME);
        var state = self.nestedClass("State");
        var errorRouter = ClassName.get(schemaPackage, ErrorRouterClassGenerator.CLASS_NAME);
        return List.of(instrumentation(self, runtime, tenantConnections, requestTenants, fetcherErrorLog,
            fetcherErrorResponse, commitPolicy, state, tenantKey, sessionHooks, errorRouter));
    }

    private static TypeSpec instrumentation(
            ClassName self, ClassName runtime, ClassName tenantConnections, ClassName requestTenants,
            ClassName fetcherErrorLog, ClassName fetcherErrorResponse, ClassName commitPolicy, ClassName state,
            TypeName tenantKey, SessionIdentityRows sessionHooks, ClassName errorRouter) {

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

        var sessionStateType = ClassName.get(errorRouter.packageName(), ConnectionRuntimeClassGenerator.SESSION_STATE_CLASS_NAME);
        var payload = sessionHooks.emitsHookImplementation()
            ? java.util.List.of(ConnectionRuntimeClassGenerator.SESSION_STATE_SLOT) : java.util.List.<String>of();
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
            .addStatement("state.mutation = executionContext.getOperationDefinition().getOperation() == $T.MUTATION",
                OPERATION)
            .beginControlFlow("if (state.mutation && !(executionContext.getMutationStrategy() instanceof $T))",
                SERIAL_STRATEGY)
            .addComment("Each top-level mutation field is one transaction, settled when the next field starts;")
            .addComment("under a parallel strategy that point would come while the previous field still runs.")
            .addStatement("throw new $T($S + executionContext.getMutationStrategy().getClass().getName())",
                IllegalStateException.class, "The Graphitron owned-connection path runs each top-level mutation"
                    + " field as one transaction and needs graphql-java's serial mutation strategy"
                    + " (AsyncSerialExecutionStrategy or a subclass); configured: ")
            .endControlFlow()
            .addStatement("state.executionContext = executionContext")
            .addCode("\n");
        if (tenantKey != null) {
            beginExecuteOperationBuilder
                .addComment("The request's tenancy, written by the generated factory as one RequestTenants value,")
                .addComment("whose constructor already copied the set and checked that a default tenant and an")
                .addComment("entity default tenant are members; the carrier owns it from here on. Absent means the ExecutionInput was not")
                .addComment("built by the generated factory, and the set has no unrestricted meaning, so fail")
                .addComment("before any fetcher runs.")
                .addStatement("$T requestTenants = graphQLContext.get($T.$L)", requestTenants,
                    tenantConnections, ConnectionRuntimeClassGenerator.TENANTS_KEY_FIELD)
                .beginControlFlow("if (requestTenants == null)")
                .addStatement("throw new $T($S)", IllegalStateException.class,
                    "No request tenant set in the GraphQL context: a <tenantColumn> build's generated"
                        + " factories (newOwnedExecutionInput / newExecutionInput) take the set of tenants"
                        + " the request may touch; build the request through one of them.")
                .endControlFlow()
                .addComment("The default and entity default tenants are the consumer's choice, never the")
                .addComment("client's, so there is no hosting probe to hide: refuse an unhosted one before any")
                .addComment("fetcher runs rather than fail every read in the request that routes on it one by one.")
                .beginControlFlow("if (requestTenants.defaultTenant().isPresent()"
                    + " && !runtime.tenantKeys().contains(requestTenants.defaultTenant().get()))")
                .addStatement("throw new $T($S + requestTenants.defaultTenant().get() + $S)",
                    IllegalStateException.class, "Default tenant '",
                    "' has no DataSource in this runtime; name a default tenant this runtime hosts.")
                .endControlFlow()
                .beginControlFlow("if (requestTenants.entityDefaultTenant().isPresent()"
                    + " && !runtime.tenantKeys().contains(requestTenants.entityDefaultTenant().get()))")
                .addStatement("throw new $T($S + requestTenants.entityDefaultTenant().get() + $S)",
                    IllegalStateException.class, "Entity default tenant '",
                    "' has no DataSource in this runtime; name an entity default tenant this runtime hosts.")
                .endControlFlow()
                .addCode("\n");
        }
        if (!payload.isEmpty()) {
            beginExecuteOperationBuilder.addComment(
                "The request's session state, written by newOwnedExecutionInput; the carrier retains it");
            beginExecuteOperationBuilder.addComment(
                "for the request, since any fetcher may trigger a mount. An input built without the");
            beginExecuteOperationBuilder.addComment(
                "factory carries none, and no operation can run without identity: fail before any SQL.");
            beginExecuteOperationBuilder
                .addStatement("$T $L = graphQLContext.get($S)", sessionStateType,
                    ConnectionRuntimeClassGenerator.SESSION_STATE_SLOT, SessionIdentityRows.CONTEXT_KEY)
                .beginControlFlow("if ($L == null)", ConnectionRuntimeClassGenerator.SESSION_STATE_SLOT)
                .addStatement("throw new $T($S)", IllegalStateException.class,
                    "This operation's input carries no session state: build it with "
                        + "Graphitron.newOwnedExecutionInput(SessionState.of(...), ...) so graphitron can "
                        + "mount the request's identity on each connection it acquires.")
                .endControlFlow();
        }
        var carrierArgs = new StringBuilder("runtime, commitPolicy");
        if (tenantKey != null) {
            carrierArgs.append(", requestTenants");
        }
        for (var p : payload) {
            carrierArgs.append(", ").append(p);
        }
        var beginExecuteOperation = beginExecuteOperationBuilder
            .addComment("The consumer's log and response for fetcher exceptions, application-scoped on the")
            .addComment("runtime; ErrorRouter reads them from here for every field of the operation.")
            .addStatement("runtime.fetcherErrorLog().ifPresent(log -> graphQLContext.put($T.class, log))",
                fetcherErrorLog)
            .addStatement("runtime.fetcherErrorResponse().ifPresent(response -> graphQLContext.put($T.class, response))",
                fetcherErrorResponse)
            .addCode("\n")
            .addComment("Publish the per-operation carrier; acquisition is lazy on every path. Fetchers")
            .addComment("resolve contexts through the carrier (getDslContext / dslFor / dslDefault), which")
            .addComment("pins and mounts one connection per key on first demand; an operation that touches")
            .addComment("no database never pins. Nothing is published under DSLContext.class: the typed key")
            .addComment("belongs to the escape-hatch factory alone.")
            .addStatement("$T carrier = new $T($L)", tenantConnections, tenantConnections, carrierArgs.toString())
            .addStatement("state.carrier = carrier")
            .addStatement("graphQLContext.put($T.class, carrier)", tenantConnections)
            .addCode("\n")
            .addComment("On every completion path (success, error, cancellation): settle the last mutation")
            .addComment("field, then release every pinned connection, in that order, in this one callback.")
            .addComment("The strategy's own completion runs from a sibling callback on the same future, which")
            .addComment("CompletableFuture may run after this one, so it cannot order the two. releaseAll() is")
            .addComment("idempotent and one entry's unmount failure never orphans another's connection.")
            .addCode("return $T.whenCompleted((result, throwable) -> {\n", SIMPLE_INSTRUMENTATION_CONTEXT)
            .addCode("    try {\n")
            .addCode("        settle(state, throwable != null);\n")
            .addCode("    } finally {\n")
            .addCode("        state.carrier.releaseAll();\n")
            .addCode("    }\n")
            .addCode("});\n")
            .addJavadoc("Publishes the per-operation connection carrier for the owned-connection path and\n"
                + "releases every pinned connection at completion; acquisition is lazy and per key, with\n"
                + "single-tenant as the one-key case. See the class javadoc for the full sequence.\n")
            .build();

        var failedField = state.nestedClass("FailedField");
        var instrumentExecutionResultBuilder = MethodSpec.methodBuilder("instrumentExecutionResult")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(COMPLETABLE_FUTURE, EXECUTION_RESULT))
            .addParameter(EXECUTION_RESULT, "executionResult")
            .addParameter(EXECUTION_PARAMS, "parameters")
            .addParameter(INSTRUMENTATION_STATE, "rawState")
            .addStatement("$T state = ($T) rawState", state, state);
        if (sessionHooks.emitsHookImplementation()) {
            instrumentExecutionResultBuilder
                .beginControlFlow("if (state != null && state.carrier != null && state.carrier.mountFailure().isPresent())")
                .addComment("No field can run without identity: the rejected mount replaces the result, escaping")
                .addComment("GraphQL.execute for the consumer to answer the request as a whole. It takes")
                .addComment("precedence over any field's settle failure.")
                .addStatement("throw state.carrier.mountFailure().get()")
                .endControlFlow();
        }
        var instrumentExecutionResult = instrumentExecutionResultBuilder
            .beginControlFlow("if (state == null || state.failedFields.isEmpty())")
            .addStatement("return $T.completedFuture(executionResult)", COMPLETABLE_FUTURE)
            .endControlFlow()
            .addComment("A field whose transaction failed to settle after it resolved is reported as failed:")
            .addComment("its value becomes null (a non-null mutation field nulls data, by null propagation)")
            .addComment("and its redacted error is added. This runs after the operation callback settled the")
            .addComment("last field, by future composition in GraphQL.executeAsync.")
            .addStatement("$T<$T, $T> data = executionResult.getData()", MAP, String.class, Object.class)
            .addStatement("$T<$T, $T> rewritten = data == null ? null : new $T<>(data)",
                MAP, String.class, Object.class, LINKED_HASH_MAP)
            .addStatement("$T<$T> errors = new $T<>(executionResult.getErrors())", LIST, GRAPHQL_ERROR, ARRAY_LIST)
            .beginControlFlow("for ($T failed : state.failedFields)", failedField)
            .beginControlFlow("if (failed.error != null)")
            .addStatement("errors.add(failed.error)")
            .endControlFlow()
            .beginControlFlow("if (failed.nullField && rewritten != null)")
            .addStatement("rewritten.put(failed.resultKey, null)")
            .beginControlFlow("if (failed.nonNull)")
            .addStatement("rewritten = null")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement("$T<$T, $T> finalData = rewritten", MAP, String.class, Object.class)
            .addStatement("return $T.completedFuture(executionResult.transform(b -> b.data(finalData).errors(errors)))",
                COMPLETABLE_FUTURE)
            .addJavadoc((sessionHooks.emitsHookImplementation()
                    ? "Throws the operation's rejected session mount, when there was one, in place of the\n"
                        + "result, so {@code GraphQL.execute} throws {@code SessionMountException} (and\n"
                        + "{@code executeAsync} completes exceptionally with it). The connection it was mounted on\n"
                        + "was already evicted at acquisition. Otherwise rewrites"
                    : "Rewrites")
                + " the result for every top-level mutation field whose\n"
                + "transaction failed to settle after the field resolved: its value becomes {@code null},\n"
                + "with null propagation for a non-null field, and its redacted error is added.\n")
            .build();

        var settle = MethodSpec.methodBuilder("settle")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(state, "state")
            .addParameter(boolean.class, "failed")
            .beginControlFlow("if (state.fieldPath == null)")
            .addStatement("return")
            .endControlFlow()
            .addStatement("$T path = state.fieldPath", RESULT_PATH)
            .addStatement("state.fieldPath = null")
            .addStatement("boolean errorUnder = errorUnder(state.executionContext, path.toList())")
            .addStatement("$T cause = state.carrier.settleField(failed || errorUnder)", Throwable.class)
            .beginControlFlow("if (cause instanceof $T nested)", tenantConnections.nestedClass("NestedScopeFailedException"))
            .addComment("A transaction a consumer's code opened inside the field failed: reported beside whatever")
            .addComment("else the field reported. The value is dropped unless the field had already failed (its")
            .addComment("own error, or a claimed payload, which describes the failure and stays).")
            .addStatement("state.failedFields.add(new $T(path.getSegmentName(), state.fieldNonNull,"
                + " $T.settleFailure(cause, state.fieldEnvironment, path), !nested.fieldReported() || errorUnder))",
                failedField, errorRouter)
            .nextControlFlow("else if (cause != null)")
            .addStatement("state.failedFields.add(new $T(path.getSegmentName(), state.fieldNonNull,"
                + " $T.settleFailure(cause, state.fieldEnvironment, path), true))", failedField, errorRouter)
            .nextControlFlow("else if (errorUnder)")
            .addComment("Rolled back because of an error under the field's path: the writes are gone, so no value")
            .addComment("describes them. The errors stand and none is added.")
            .addStatement("state.failedFields.add(new $T(path.getSegmentName(), state.fieldNonNull, null, true))",
                failedField)
            .endControlFlow()
            .addJavadoc("Settles the running top-level mutation field, if any: its transaction commits unless\n"
                + "it failed ({@code failed}, an error recorded under its path, or a mark from\n"
                + "{@code ErrorRouter}). A failure settling itself discovers is recorded for\n"
                + "{@link #instrumentExecutionResult} to report.\n")
            .build();

        var errorUnder = MethodSpec.methodBuilder("errorUnder")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(EXECUTION_CONTEXT, "executionContext")
            .addParameter(ParameterizedTypeName.get(LIST, ClassName.get(Object.class)), "path")
            .beginControlFlow("for ($T error : executionContext.getErrors())", GRAPHQL_ERROR)
            .addStatement("$T<$T> at = error.getPath()", LIST, Object.class)
            .beginControlFlow("if (at != null && at.size() >= path.size() && at.subList(0, path.size()).equals(path))")
            .addStatement("return true")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return false")
            .addJavadoc("Whether an error recorded so far lies at or under {@code path}.\n")
            .build();

        var beginFieldExecution = MethodSpec.methodBuilder("beginFieldExecution")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(INSTRUMENTATION_CONTEXT, ClassName.get(Object.class)))
            .addParameter(FIELD_PARAMS, "parameters")
            .addParameter(INSTRUMENTATION_STATE, "rawState")
            .addStatement("$T state = ($T) rawState", state, state)
            .addStatement("$T info = parameters.getExecutionStepInfo()", EXECUTION_STEP_INFO)
            .beginControlFlow("if (state != null && state.mutation && state.carrier != null && info.getPath().getLevel() == 1)")
            .addComment("Under the serial strategy a top-level field starts only once the previous one's value,")
            .addComment("payload included, is complete: the previous field's own completion callback comes")
            .addComment("earlier than that, so it settles here.")
            .addStatement("settle(state, false)")
            .addStatement("state.fieldPath = info.getPath()")
            .addStatement("state.fieldNonNull = info.isNonNullType()")
            .addStatement("state.fieldEnvironment = null")
            .addStatement("state.carrier.beginField(info.getPath().toList())")
            .endControlFlow()
            .addStatement("return null")
            .addJavadoc("Settles the previous top-level mutation field and begins the next; its transaction\n"
                + "opens on each connection the field first acquires.\n")
            .build();

        var beginFieldFetching = MethodSpec.methodBuilder("beginFieldFetching")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(FIELD_FETCHING_CONTEXT)
            .addParameter(FIELD_FETCH_PARAMS, "parameters")
            .addParameter(INSTRUMENTATION_STATE, "rawState")
            .addStatement("$T state = ($T) rawState", state, state)
            .beginControlFlow("if (state != null && state.fieldPath != null && state.fieldEnvironment == null"
                + " && parameters.getExecutionStepInfo().getPath().equals(state.fieldPath))")
            .addComment("The settle-failure report goes through the consumer's FetcherErrorLog and")
            .addComment("FetcherErrorResponse, which take the field's environment.")
            .addStatement("state.fieldEnvironment = parameters.getEnvironment()")
            .endControlFlow()
            .addStatement("return null")
            .addJavadoc("Captures the running top-level mutation field's environment, for a settle failure's\n"
                + "report.\n")
            .build();

        var failedFieldType = TypeSpec.classBuilder("FailedField")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("A top-level mutation field that rolled back after it resolved: where it sits in the\n"
                + "result, whether null propagation reaches {@code data}, the error to add (none when the\n"
                + "errors under its path already stand), and whether its value is dropped.\n")
            .addField(FieldSpec.builder(String.class, "resultKey", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(boolean.class, "nonNull", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(GRAPHQL_ERROR, "error", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(boolean.class, "nullField", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(String.class, "resultKey")
                .addParameter(boolean.class, "nonNull")
                .addParameter(GRAPHQL_ERROR, "error")
                .addParameter(boolean.class, "nullField")
                .addStatement("this.resultKey = resultKey")
                .addStatement("this.nonNull = nonNull")
                .addStatement("this.error = error")
                .addStatement("this.nullField = nullField")
                .build())
            .build();

        var stateType = TypeSpec.classBuilder("State")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(INSTRUMENTATION_STATE)
            .addField(FieldSpec.builder(tenantConnections, "carrier", Modifier.PRIVATE).build())
            .addField(FieldSpec.builder(EXECUTION_CONTEXT, "executionContext", Modifier.PRIVATE).build())
            .addField(FieldSpec.builder(boolean.class, "mutation", Modifier.PRIVATE).build())
            .addField(FieldSpec.builder(RESULT_PATH, "fieldPath", Modifier.PRIVATE)
                .addJavadoc("The running top-level mutation field's path, {@code null} between fields.\n")
                .build())
            .addField(FieldSpec.builder(boolean.class, "fieldNonNull", Modifier.PRIVATE).build())
            .addField(FieldSpec.builder(DATA_FETCHING_ENVIRONMENT, "fieldEnvironment", Modifier.PRIVATE).build())
            .addField(FieldSpec.builder(ParameterizedTypeName.get(LIST, failedField), "failedFields",
                    Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", ARRAY_LIST)
                .build())
            .addType(failedFieldType)
            .addJavadoc("Per-request instrumentation state: the connection carrier to release at completion,\n"
                + "and the running top-level mutation field. Read and written on the dispatch thread:\n"
                + "graphql-java calls the field hooks there, and the serial strategy starts a field only\n"
                + "once the previous one completed.\n")
            .build();

        var builder = TypeSpec.classBuilder(CLASS_NAME)
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
            .addMethod(beginFieldExecution)
            .addMethod(beginFieldFetching)
            .addMethod(instrumentExecutionResult)
            .addMethod(settle)
            .addMethod(errorUnder);
        return builder
            .addType(stateType)
            .build();
    }
}
