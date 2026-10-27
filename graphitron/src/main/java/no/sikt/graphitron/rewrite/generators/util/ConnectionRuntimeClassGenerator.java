package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.model.grammar.ArgMappingSigil;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.session.SessionHooks;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Emits the connection-lifecycle runtime substrate into the consumer's
 * {@code <outputPackage>.schema} package: an application-scoped {@code GraphitronRuntime} that owns
 * the sources ({@code DataSource} + dialect + jOOQ {@code Settings} triples), the
 * acquisition-scoped {@code PinnedConnection}, and, when {@code <sessionState>} names a method
 * pair, the generated hook class whose static {@code mount}/{@code unmount} call the consumer's
 * own methods directly.
 *
 * <p>Emitted (not shipped as a graphitron artifact), following the {@code GraphitronContext}
 * precedent: the bodies depend only on the JDK, jOOQ, and (for the hook class) the consumer's own
 * named classes; no auth-framework type and no graphitron type ever appears. The
 * {@code TenantConnections} carrier additionally references graphql-java's
 * {@code DataFetchingEnvironment} (its routing statics resolve the carrier off the GraphQL
 * context), which every generated consumer already has on the classpath. The bodies must be valid
 * Java 17 (verified by the {@code graphitron-sakila-example} {@code <release>17</release>}
 * compile).
 *
 * <h2>The two lifecycles</h2>
 * Connection setup is application-scoped ({@code GraphitronRuntime}, built once at wiring time via
 * {@code Graphitron.runtime(dataSource, dialect)}); identity is acquisition-scoped
 * ({@code PinnedConnection}, one per pinned connection). {@code PinnedConnection} carries
 * <em>no</em> transaction concept: the commit-policy axis (commit-vs-rollback) is the orthogonal
 * transaction concern that the {@code TransactionProvider} and execution instrumentation layer
 * over this seam. The mount's return value is the only thing called a "handle" here.
 *
 * <h2>The lifecycle contract (unit-pinned in {@code ConnectionRuntimeClassGeneratorTest})</h2>
 * <ul>
 *   <li><b>Acquire.</b> {@code DataSource.getConnection()}, then {@code setAutoCommit(true)}, then
 *       the mount method with the typed payload, capturing its returned handle. <b>Fail
 *       closed:</b> if mount throws (it may have partially mounted session state first), the
 *       connection is evicted, never returned, and the failure propagates before any operation
 *       SQL runs.</li>
 *   <li><b>Release.</b> Any transaction the operation left open is rolled back and autocommit
 *       asserted, then the unmount method (when configured) fires on <em>every</em> completion
 *       path (success, error, cancellation), bound to the captured handle when it takes one;
 *       release is idempotent. <b>Evict on unmount failure:</b> if unmount throws or cannot run,
 *       the physical connection is aborted and never returned to the pool, so a connection whose
 *       identity cannot be proven unmounted gets no next borrower.</li>
 * </ul>
 * Eviction uses {@link java.sql.Connection#abort(java.util.concurrent.Executor)} (JDBC, valid Java
 * 17): pool wrappers (Agroal/HikariCP) honour it as a true physical evict where {@code close()}
 * merely reclaims the connection to the pool. The runtime supplies a same-thread executor.
 *
 * <h3>Graphitron asserts the connection's transaction mode</h3>
 * Graphitron checks the connection out of the pool, holds it for the operation, and hands it
 * back, so its transaction mode is something graphitron <em>asserts</em>, never something it
 * inherits or preserves. Autocommit-on is the resting state every phase requires: the mount must
 * be its own committed transaction (session-scoped Postgres state such as {@code set_config} and
 * {@code SET ROLE} survives a {@code COMMIT} but is reverted when the transaction that issued it
 * rolls back, so a mount inside a lazily-opened transaction would be unwound by the first failing
 * mutation field, leaving the rest of the operation running unmounted, a wrong answer under RLS
 * rather than an error), queries want no transaction at all rather than an idle-in-transaction
 * snapshot, and the unmount at release must take effect immediately. Asserting the mode at
 * acquire excludes all of that before any SQL runs. The same fact read from the other end is why
 * {@code release} rolls back <em>before</em> switching the mode: JDBC commits an open transaction
 * when autocommit is turned on, so that order is load-bearing too. What the assertion asks of a
 * consumer's mount is a documented precondition on the hook contract: mount session-scoped state,
 * in the database's own session vocabulary; transaction-scoped storage ({@code SET LOCAL},
 * {@code ON COMMIT DELETE ROWS} rows) is gone by the mount's own implicit commit and never worked.
 *
 * <h2>Load-bearing invariant: one connection per operation, one thread per connection</h2>
 * Pinning exactly one connection per distinct source within an operation is safe because generated
 * batch loaders execute SQL synchronously on the dispatch thread
 * ({@code RowsMethodCall.batchLoaderLambda} emits
 * {@code CompletableFuture.completedFuture(rows(keys, dfe))}). That invariant is pinned at its
 * emission site by {@code RowsMethodCallTest}'s synchronous-body assertion; a future "make loaders
 * async" change fails there rather than as a distant execution-tier flake. See that test's javadoc,
 * which links back here.
 *
 * <p>In multi-tenant builds the carrier's {@code scatter} helper is the one deliberate exception,
 * and it preserves both halves of the invariant for a revised reason: concurrency is confined to
 * scatter's bounded workers, each owning exactly one keyed connection single-threaded through
 * {@code dslFor(key)}, while the dispatch thread is blocked inside the join for the scatter's whole
 * duration (so it cannot race the workers, and the default connection stays dispatch-owned,
 * structurally: workers receive only the keyed {@code DSLContext}). Generated fetchers stay
 * synchronous; the fanned fetcher calls {@code scatter} and blocks, and concurrency never leaks
 * into fetcher bodies.
 *
 * <h2>Session hooks</h2>
 * With no {@code <sessionState>} configured nothing is emitted and {@code acquire} carries no
 * mount call at all: no hook unit, no runtime field, no dispatch. A configured method pair
 * ({@link SessionHooks.HandleLess} / {@link SessionHooks.Handled}) additionally emits
 * {@value #SESSION_HOOK_IMPL_CLASS_NAME}, one final class with static {@code mount} and
 * {@code unmount} methods that {@code PinnedConnection} calls directly: the choice between a
 * mount and no mount is known at generation time, so it is a choice about what to emit, never a
 * value the runtime holds or dispatches polymorphically. The hook class is where the
 * provider-free {@code Configuration} is built (from the connection and the resolved source's
 * dialect and settings, so a consumer's schema mapping reaches their own mount method) and where
 * the payload is spread into the mount's own declaration order; {@code PinnedConnection} stays
 * free of both.
 */
public final class ConnectionRuntimeClassGenerator {

    public static final String RUNTIME_CLASS_NAME = "GraphitronRuntime";
    public static final String PINNED_CONNECTION_CLASS_NAME = "PinnedConnection";
    /** The generated hook class emitted from a configured {@code <sessionState>} method pair. */
    public static final String SESSION_HOOK_IMPL_CLASS_NAME = "GraphitronSessionHook";
    /**
     * The request-scoped rejection a throwing mount becomes, emitted beside the hook class and
     * only with it: a build that mounts nothing has nothing to reject.
     */
    public static final String SESSION_MOUNT_EXCEPTION_CLASS_NAME = "SessionMountException";
    /** The per-operation tenant-keyed connection carrier. */
    public static final String TENANT_CONNECTIONS_CLASS_NAME = "TenantConnections";
    /**
     * The carrier's per-element tenant stamp, {@code <T> DataFetcherResult<T> handDown(T row, K
     * tenantKey)}, shared by the fan-out union and node and entity dispatch.
     */
    public static final String HAND_DOWN_METHOD = "handDown";
    /**
     * The request-tenancy value the factory takes in {@code <tenantColumn>} builds: the request
     * tenant set and an optional default tenant that is a member of it.
     */
    public static final String REQUEST_TENANTS_CLASS_NAME = "RequestTenants";
    /** The runtime's nested source triple ({@code DataSource}, dialect, jOOQ {@code Settings}). */
    public static final String SOURCE_CLASS_NAME = "Source";
    /**
     * The consumer-implemented callback that decides how a fetcher exception is logged, both one no
     * {@code @error} mapping claims and one a handler caught; held by the runtime and read by
     * {@code ErrorRouter.toConfiguredLog}.
     */
    public static final String FETCHER_ERROR_LOG_CLASS_NAME = "FetcherErrorLog";
    /**
     * The consumer-implemented callback that decides what the client reads for a fetcher
     * exception: the reference, a caught error's {@code message:} and the redacted top-level text;
     * held by the runtime beside {@link #FETCHER_ERROR_LOG_CLASS_NAME} and read by {@code ErrorRouter}.
     */
    public static final String FETCHER_ERROR_RESPONSE_CLASS_NAME = "FetcherErrorResponse";

    private static final ClassName CONNECTION = ClassName.get("java.sql", "Connection");
    private static final ClassName SQL_EXCEPTION = ClassName.get("java.sql", "SQLException");
    private static final ClassName DATA_SOURCE = ClassName.get("javax.sql", "DataSource");
    private static final ClassName SETTINGS = ClassName.get("org.jooq.conf", "Settings");
    private static final ClassName EXECUTOR = ClassName.get("java.util.concurrent", "Executor");
    private static final ClassName EXECUTORS = ClassName.get("java.util.concurrent", "Executors");
    private static final ClassName SQL_DIALECT = ClassName.get("org.jooq", "SQLDialect");
    private static final ClassName OBJECTS = ClassName.get("java.util", "Objects");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName HASH_MAP = ClassName.get("java.util", "HashMap");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName LINKED_HASH_SET = ClassName.get("java.util", "LinkedHashSet");
    private static final ClassName NO_SUCH_ELEMENT = ClassName.get("java.util", "NoSuchElementException");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName COLLECTION = ClassName.get("java.util", "Collection");
    private static final ClassName DURATION = ClassName.get("java.time", "Duration");
    private static final ClassName FUNCTION = ClassName.get("java.util.function", "Function");
    private static final ClassName CONCURRENT_HASH_MAP = ClassName.get("java.util.concurrent", "ConcurrentHashMap");
    private static final ClassName COMPLETABLE_FUTURE = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName COMPLETION_EXCEPTION = ClassName.get("java.util.concurrent", "CompletionException");
    private static final ClassName EXECUTION_EXCEPTION = ClassName.get("java.util.concurrent", "ExecutionException");
    private static final ClassName TIMEOUT_EXCEPTION = ClassName.get("java.util.concurrent", "TimeoutException");
    private static final ClassName TIME_UNIT = ClassName.get("java.util.concurrent", "TimeUnit");
    private static final ClassName ATOMIC_INTEGER = ClassName.get("java.util.concurrent.atomic", "AtomicInteger");
    private static final ClassName ATOMIC_REFERENCE = ClassName.get("java.util.concurrent.atomic", "AtomicReference");
    private static final ClassName THREAD_LOCAL = ClassName.get("java.lang", "ThreadLocal");
    private static final TypeName OBJECT_KEY = ClassName.get(Object.class);
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DATA_TYPE = ClassName.get("org.jooq", "DataType");
    private static final ClassName DATA_FETCHING_ENVIRONMENT = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName DATA_ACCESS_EXCEPTION = ClassName.get("org.jooq.exception", "DataAccessException");
    private static final ClassName DATA_FETCHER_RESULT = ClassName.get("graphql.execution", "DataFetcherResult");
    private static final ClassName GRAPHQL_ERROR = ClassName.get("graphql", "GraphQLError");
    private static final ClassName GRAPHQL_ERROR_BUILDER = ClassName.get("graphql", "GraphqlErrorBuilder");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");
    private static final ClassName UUID_CLASS = ClassName.get("java.util", "UUID");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    /**
     * The generated {@code mount}/{@code acquire} parameter carrying the tenant a connection is
     * mounted for in multi-tenant builds. Not {@code tenant}: those methods also declare the
     * consumer's payload parameters under their own names, and a payload named {@code tenant} is
     * plausible in exactly these builds.
     */
    static final String MOUNTED_TENANT = "mountedTenant";

    /** The graphQLContext key the request tenant set is published under. */
    public static final String TENANTS_KEY_FIELD = "TENANTS_KEY";
    /** The literal value of the emitted {@code TENANTS_KEY} constant (also written by the factory). */
    public static final String TENANTS_KEY_VALUE = "no.sikt.graphitron.request.tenants";
    /**
     * The client-facing {@code extensions.classification} vocabulary for per-tenant fan-out
     * failures: one value per non-success {@code Outcome} arm, emitted as named constants on the
     * carrier's {@code FanOutFailure} so the wire contract is single-sourced and greppable.
     */
    public static final String FAN_OUT_FAILED_CLASSIFICATION = "TenantFanOutFailed";
    /** {@link #FAN_OUT_FAILED_CLASSIFICATION}'s deadline sibling. */
    public static final String FAN_OUT_TIMED_OUT_CLASSIFICATION = "TenantFanOutTimedOut";

    private ConnectionRuntimeClassGenerator() {}

    /**
     * @param outputPackage the consumer's root output package; the classes are emitted into
     *                      {@code outputPackage + ".schema"} (beside {@code GraphitronContext})
     * @param sessionHooks  the resolved session-hook carrier: {@link SessionHooks.NotConfigured}
     *                      emits no hook unit and no mount call; a configured arm additionally
     *                      emits {@link #SESSION_HOOK_IMPL_CLASS_NAME}, called directly
     */
    public static List<TypeSpec> generate(String outputPackage, SessionHooks sessionHooks) {
        return generate(outputPackage, sessionHooks, null, null);
    }

    /**
     * Canonical form carrying the divined tenant key type and the representative tenant column.
     * {@code tenantKeyType} is the tenant Java type read off the jOOQ catalog's tenant column when
     * {@code <tenantColumn>} is configured, or {@code null} for single-tenant builds. A configured
     * type replaces the erased {@code Object} on every tenant-keyed surface (the constructor map,
     * the keyed acquisition, the per-operation carrier), so a consumer wiring a map keyed with
     * the wrong type is a compile error rather than a first-request lookup miss.
     *
     * <p>{@code tenantColumn} is the representative column rendered as
     * {@code <Tables>.<TABLE>.<COLUMN>}, whose {@code DataType} and converter decide tenant key
     * equality: every key graphitron receives is put through the converter and back before it is
     * compared, so two spellings the column stores as one database value name one tenant. Both
     * arguments are {@code null} for a single-tenant build or both present.
     *
     * @throws IllegalArgumentException when exactly one of the two is {@code null}
     */
    public static List<TypeSpec> generate(String outputPackage, SessionHooks sessionHooks,
                                          TypeName tenantKeyType, CodeBlock tenantColumn) {
        if ((tenantKeyType == null) != (tenantColumn == null)) {
            throw new IllegalArgumentException("A multi-tenant runtime needs both the tenant key type and"
                + " the representative tenant column; a single-tenant one neither (key type: "
                + tenantKeyType + ", column: " + tenantColumn + ")");
        }
        TypeName tenantKey = tenantKeyType == null
            ? OBJECT_KEY
            : (tenantKeyType.isPrimitive() ? tenantKeyType.box() : tenantKeyType);
        String schemaPackage = outputPackage + ".schema";
        var sessionHookImpl = ClassName.get(schemaPackage, SESSION_HOOK_IMPL_CLASS_NAME);
        var sessionMountException = ClassName.get(schemaPackage, SESSION_MOUNT_EXCEPTION_CLASS_NAME);
        var pinnedConnection = ClassName.get(schemaPackage, PINNED_CONNECTION_CLASS_NAME);
        var runtime = ClassName.get(schemaPackage, RUNTIME_CLASS_NAME);
        var tenantConnections = ClassName.get(schemaPackage, TENANT_CONNECTIONS_CLASS_NAME);
        var requestTenants = ClassName.get(schemaPackage, REQUEST_TENANTS_CLASS_NAME);
        var fetcherErrorLog = ErrorRouterClassGenerator.fetcherErrorLogClass(outputPackage);
        var fetcherErrorResponse = ErrorRouterClassGenerator.fetcherErrorResponseClass(outputPackage);
        var errorRouter = ClassName.get(schemaPackage, ErrorRouterClassGenerator.CLASS_NAME);
        var instrumentation = ClassName.get(schemaPackage, GraphitronConnectionInstrumentationGenerator.CLASS_NAME);
        var provider = ClassName.get(schemaPackage, GraphitronTransactionProviderGenerator.CLASS_NAME);
        var commitPolicy = provider.nestedClass(GraphitronTransactionProviderGenerator.COMMIT_POLICY_ENUM_NAME);
        var columnValues = ClassName.get(outputPackage + ".util", ColumnValuesClassGenerator.CLASS_NAME);

        boolean multiTenant = tenantKeyType != null;

        var units = new ArrayList<TypeSpec>();
        units.add(pinnedConnection(pinnedConnection, sessionHookImpl, sessionMountException, sessionHooks,
            tenantKey, multiTenant));
        units.add(runtime(runtime, pinnedConnection, instrumentation, fetcherErrorLog, fetcherErrorResponse,
            sessionHooks, tenantKey, multiTenant, tenantConnections));
        units.add(tenantConnections(tenantConnections, runtime, pinnedConnection, provider, commitPolicy, tenantKey,
            multiTenant, sessionHooks, requestTenants, tenantColumn, errorRouter, columnValues, sessionMountException));
        if (multiTenant) {
            units.add(requestTenants(requestTenants, tenantConnections, tenantKey));
        }
        units.add(fetcherErrorLog(fetcherErrorLog, multiTenant ? tenantKey : null));
        units.add(fetcherErrorResponse(fetcherErrorResponse));
        if (sessionHooks.emitsHookImplementation()) {
            units.add(sessionHookImpl(sessionHooks, tenantKey, multiTenant));
            units.add(sessionMountException(sessionMountException));
        }
        return List.copyOf(units);
    }

    /** Back-compatible overload for callers that mount no identity (unit-tier drivers, no {@code <sessionState>}). */
    public static List<TypeSpec> generate(String outputPackage) {
        return generate(outputPackage, SessionHooks.NotConfigured.INSTANCE);
    }

    /**
     * The acquisition-scoped pinned connection: acquire/release lifecycle with fail-closed + evict.
     * Multi-tenant builds additionally emit {@code abort()}, the straggler seam the scatter helper's
     * timeout path routes through: evict without the unmount hook, safe against a worker that may
     * still be executing on the connection. The emitted shape follows the resolved
     * {@link SessionHooks} arm: no hook configured means no mount call at all; a handle-less mount
     * means no handle field; a handled mount adds the typed handle field {@code release} reads.
     * Multi-tenant builds give {@code acquire} an {@code Optional<K>} parameter ({@value #MOUNTED_TENANT}) naming the
     * tenant the connection is acquired for, handed on to the mount.
     */
    private static TypeSpec pinnedConnection(ClassName pinnedConnection, ClassName sessionHookImpl,
            ClassName sessionMountException, SessionHooks sessionHooks, TypeName tenantKey, boolean multiTenant) {
        var payload = payloadParams(sessionHooks);
        boolean mounts = sessionHooks.emitsHookImplementation();
        boolean unmounts = sessionHooks.unmountRef().isPresent();
        TypeName handleType = sessionHooks instanceof SessionHooks.Handled handled ? handled.handleType() : null;
        boolean unmountTakesHandle = unmounts && sessionHooks.unmountRef().orElseThrow().params().stream()
            .anyMatch(p -> p.source() instanceof ParamSource.SessionHandle);

        var connectionField = FieldSpec.builder(CONNECTION, "connection", Modifier.PRIVATE, Modifier.FINAL).build();
        var dialectField = FieldSpec.builder(SQL_DIALECT, "dialect", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The resolved source's dialect, carried with the connection it describes so the\n"
                + "carrier's context minting and the unmount call read the entry's own source.\n")
            .build();
        var settingsField = FieldSpec.builder(SETTINGS, "settings", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The resolved source's jOOQ {@code Settings}, beside {@link #dialect}.\n")
            .build();
        var handleField = handleType == null ? null
            : FieldSpec.builder(handleType, "handle", Modifier.PRIVATE, Modifier.FINAL)
                .addJavadoc("The mount's returned handle: written once at acquisition, read by release\n"
                    + "(to pass to unmount) and published on the carrier entry's {@code Configuration}\n"
                    + "for {@code $$session}-bound service parameters.\n")
                .build();
        var abortExecutorField = FieldSpec.builder(EXECUTOR, "abortExecutor", Modifier.PRIVATE, Modifier.FINAL).build();
        var releasedField = FieldSpec.builder(boolean.class, "released", Modifier.PRIVATE).build();

        var constructorBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .addParameter(CONNECTION, "connection")
            .addParameter(SQL_DIALECT, "dialect")
            .addParameter(SETTINGS, "settings");
        if (handleType != null) {
            constructorBuilder.addParameter(handleType, "handle");
        }
        constructorBuilder
            .addParameter(EXECUTOR, "abortExecutor")
            .addStatement("this.connection = connection")
            .addStatement("this.dialect = dialect")
            .addStatement("this.settings = settings");
        if (handleType != null) {
            constructorBuilder.addStatement("this.handle = handle");
        }
        var constructor = constructorBuilder
            .addStatement("this.abortExecutor = abortExecutor")
            .build();

        var acquireBuilder = MethodSpec.methodBuilder("acquire")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(pinnedConnection)
            .addParameter(DATA_SOURCE, "dataSource")
            .addParameter(SQL_DIALECT, "dialect")
            .addParameter(SETTINGS, "settings");
        if (multiTenant) {
            acquireBuilder.addParameter(ParameterizedTypeName.get(OPTIONAL, tenantKey), MOUNTED_TENANT);
        }
        acquireBuilder.addParameter(EXECUTOR, "abortExecutor");
        for (var p : payload) {
            acquireBuilder.addParameter(p.javaType(), p.name());
        }
        acquireBuilder
            .addException(SQL_EXCEPTION)
            .addStatement("$T connection = dataSource.getConnection()", CONNECTION);
        if (handleType != null) {
            acquireBuilder.addStatement("$T handle", handleType);
        }
        acquireBuilder
            .beginControlFlow("try")
            .addComment("Graphitron asserts the connection's transaction mode: autocommit-on is the resting")
            .addComment("state on a connection it owns, whatever the pool lent. The mount must be its own")
            .addComment("committed transaction (session-scoped state set inside a lazily-opened transaction")
            .addComment("would be reverted by the first failing mutation field's rollback), so the assertion")
            .addComment("runs before any hook SQL.")
            .addStatement("connection.setAutoCommit(true)");
        if (mounts) {
            String args = mountCallArgs(payload, multiTenant);
            acquireBuilder
                .addComment("Only the mount itself is a rejected identity: a failed autocommit assertion above is")
                .addComment("a deployment fault and must not reach a consumer's mapping of identity rejections.")
                .beginControlFlow("try");
            if (handleType != null) {
                acquireBuilder.addStatement("handle = $T.mount($L)", sessionHookImpl, args);
            } else {
                acquireBuilder.addStatement("$T.mount($L)", sessionHookImpl, args);
            }
            acquireBuilder
                .nextControlFlow("catch ($T rejected)", Exception.class)
                .addStatement("throw new $T(rejected)", sessionMountException)
                .endControlFlow();
        }
        acquireBuilder
            .nextControlFlow("catch ($T mountFailure)", Throwable.class)
            .addComment("Fail closed: the mount may have partially mounted session state before throwing.")
            .addComment("Evict rather than return a half-mounted connection to the pool; reject before any SQL.")
            .addStatement("evict(connection, abortExecutor)")
            .addStatement("throw rethrow(mountFailure)")
            .endControlFlow();
        if (handleType != null) {
            acquireBuilder.addStatement("return new $T(connection, dialect, settings, handle, abortExecutor)",
                pinnedConnection);
        } else {
            acquireBuilder.addStatement("return new $T(connection, dialect, settings, abortExecutor)",
                pinnedConnection);
        }
        var acquire = acquireBuilder
            .addJavadoc(mounts
                ? "Pins one connection, asserts autocommit on it, and mounts identity through the generated\n"
                    + "hook's static {@code mount}, with the typed payload. The mount therefore always runs\n"
                    + "outside any transaction, as its own committed statement, whatever the pool's\n"
                    + "configuration. Fail-closed: a throwing mount (or a failed autocommit assertion)\n"
                    + "evicts the connection and propagates before any operation SQL runs; the mount's own\n"
                    + "throw propagates as {@code SessionMountException}, the assertion's as itself.\n"
                : "Pins one connection and asserts autocommit on it (the resting state graphitron holds an\n"
                    + "owned connection in). No {@code <sessionState>} is configured, so no identity is\n"
                    + "mounted and no hook runs. Fail-closed: a failed assertion evicts the connection.\n")
            .build();

        var connectionAccessor = MethodSpec.methodBuilder("connection")
            .addModifiers(Modifier.PUBLIC)
            .returns(CONNECTION)
            .addStatement("return connection")
            .addJavadoc("The pinned connection every fetch of this operation runs on.\n")
            .build();

        var dialectAccessor = MethodSpec.methodBuilder("dialect")
            .addModifiers(Modifier.PUBLIC)
            .returns(SQL_DIALECT)
            .addStatement("return dialect")
            .addJavadoc("The resolved source's dialect this connection was acquired under.\n")
            .build();

        var settingsAccessor = MethodSpec.methodBuilder("settings")
            .addModifiers(Modifier.PUBLIC)
            .returns(SETTINGS)
            .addStatement("return settings")
            .addJavadoc("The resolved source's jOOQ {@code Settings} this connection was acquired under.\n")
            .build();

        var releaseBuilder = MethodSpec.methodBuilder("release")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .beginControlFlow("if (released)")
            .addStatement("return")
            .endControlFlow()
            .addStatement("released = true")
            .beginControlFlow("try")
            .addComment("Graphitron asserts autocommit on a connection it owns: if the operation left a")
            .addComment("transaction open (e.g. it died mid-mutation before the provider settled), roll it")
            .addComment("back first, because JDBC commits an open transaction when autocommit is turned on;")
            .addComment("only then assert the mode, so the unmount's clears take effect immediately rather")
            .addComment("than sitting in an uncommitted transaction.")
            .beginControlFlow("if (!connection.getAutoCommit())")
            .addStatement("connection.rollback()")
            .addStatement("connection.setAutoCommit(true)")
            .endControlFlow();
        if (unmounts) {
            String handleArg = unmountTakesHandle ? ", handle" : "";
            releaseBuilder.addStatement("$T.unmount(connection, dialect, settings$L)", sessionHookImpl, handleArg);
        }
        var release = releaseBuilder
            .nextControlFlow("catch ($T releaseFailure)", Throwable.class)
            .addComment("Identity cannot be proven unmounted: evict the physical connection, never return it.")
            .addStatement("evict(connection, abortExecutor)")
            .addStatement("throw rethrow(releaseFailure)")
            .endControlFlow()
            .addStatement("closeReturningToPool(connection)")
            .addJavadoc((unmounts
                ? "Unmounts identity and releases the connection, settling any transaction the operation\n"
                    + "left open first so the unmount runs outside any transaction. Fires unmount on every\n"
                    + "completion path (success, error, cancellation); idempotent, so a redundant\n"
                    + "cancel-then-complete release unmounts exactly once. Evicts on unmount failure (and on\n"
                    + "a failed pre-unmount settle, which equally leaves identity unprovable).\n"
                : "Releases the connection, settling any transaction the operation left open and asserting\n"
                    + "autocommit (the resting state graphitron returns an owned connection in"
                    + (mounts
                        ? "; the next\nrequest's mount overwrites this one's identity wholesale, the mount-only contract"
                        : "")
                    + ").\nIdempotent; evicts on a failed settle.\n"))
            .build();

        var close = MethodSpec.methodBuilder("close")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addStatement("release()")
            .addJavadoc("{@link AutoCloseable} alias for {@link #release()}.\n")
            .build();

        var evict = MethodSpec.methodBuilder("evict")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(void.class)
            .addParameter(CONNECTION, "connection")
            .addParameter(EXECUTOR, "abortExecutor")
            .beginControlFlow("try")
            .addComment("abort() is the JDBC evict primitive: pool wrappers discard the physical connection")
            .addComment("rather than reclaiming it, and it also covers a connection too dead to close cleanly.")
            .addStatement("connection.abort(abortExecutor)")
            .nextControlFlow("catch ($T abortFailure)", Throwable.class)
            .addComment("abort unsupported or itself failing: fall back to close so the connection never leaks.")
            .beginControlFlow("try")
            .addStatement("connection.close()")
            .nextControlFlow("catch ($T ignored)", Throwable.class)
            .addComment("nothing left to do; the connection is already unusable.")
            .endControlFlow()
            .endControlFlow()
            .build();

        var closeReturningToPool = MethodSpec.methodBuilder("closeReturningToPool")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(void.class)
            .addParameter(CONNECTION, "connection")
            .beginControlFlow("try")
            .addStatement("connection.close()")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T(e)", RuntimeException.class)
            .endControlFlow()
            .build();

        var rethrow = MethodSpec.methodBuilder("rethrow")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(RuntimeException.class)
            .addParameter(Throwable.class, "cause")
            .beginControlFlow("if (cause instanceof $T e)", Error.class)
            .addStatement("throw e")
            .endControlFlow()
            .beginControlFlow("if (cause instanceof $T e)", RuntimeException.class)
            .addStatement("return e")
            .endControlFlow()
            .addStatement("return new $T(cause)", RuntimeException.class)
            .addJavadoc("Turns a caught {@code cause} into an unchecked throwable to rethrow: {@link Error}\n"
                + "is rethrown as-is, a {@link RuntimeException} is returned unchanged, and the checked\n"
                + "residue (a mount/unmount {@code SQLException}) is wrapped. Callers write\n"
                + "{@code throw rethrow(cause)}.\n")
            .build();

        var builder = TypeSpec.classBuilder(PINNED_CONNECTION_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(AutoCloseable.class)
            .addJavadoc("One pinned connection"
                + (mounts ? " with per-request identity mounted" : "")
                + " for its acquisition-scoped lifetime.\n"
                + "Carries no transaction machinery (the {@code TransactionProvider} and execution\n"
                + "instrumentation compose over this seam) and no payload: the per-key carrier holds the\n"
                + "payload for the request and hands it to {@code acquire}. See\n"
                + "{@code ConnectionRuntimeClassGenerator} for the full lifecycle contract.\n")
            .addField(connectionField)
            .addField(dialectField)
            .addField(settingsField);
        if (handleField != null) {
            builder.addField(handleField);
        }
        builder.addField(abortExecutorField)
            .addField(releasedField)
            .addMethod(constructor)
            .addMethod(acquire)
            .addMethod(connectionAccessor)
            .addMethod(dialectAccessor)
            .addMethod(settingsAccessor);
        if (handleType != null) {
            builder.addMethod(MethodSpec.methodBuilder("handle")
                .addModifiers(Modifier.PUBLIC)
                .returns(handleType)
                .addStatement("return handle")
                .addJavadoc("The mount's returned handle for this connection.\n")
                .build());
        }
        builder.addMethod(release);
        if (multiTenant) {
            builder.addMethod(abortMethod());
        }
        return builder
            .addMethod(close)
            .addMethod(evict)
            .addMethod(closeReturningToPool)
            .addMethod(rethrow)
            .build();
    }

    /** The mount's payload parameters as typed params, empty when nothing is configured. */
    private static List<MethodRef.Param.Typed> payloadParams(SessionHooks sessionHooks) {
        return sessionHooks.mountRef()
            .map(m -> m.params().stream()
                .filter(p -> p instanceof MethodRef.Param.Typed typed
                    && typed.source() instanceof ParamSource.Context)
                .map(p -> (MethodRef.Param.Typed) p)
                .toList())
            .orElse(List.of());
    }

    /**
     * The argument list for the generated hook's static {@code mount}: the connection, the
     * resolved source's dialect and settings, the tenant in multi-tenant builds, then the payload
     * in the generated mount's parameter order (the generated mount spreads it into the
     * consumer's method's own declaration order).
     */
    private static String mountCallArgs(List<MethodRef.Param.Typed> payload, boolean multiTenant) {
        var args = new StringBuilder("connection, dialect, settings");
        if (multiTenant) {
            args.append(", ").append(MOUNTED_TENANT);
        }
        for (var p : payload) {
            args.append(", ").append(p.name());
        }
        return args.toString();
    }

    /**
     * {@code abort()}: the straggler release path. A scatter worker past its deadline may still be
     * mid-statement on this connection, so neither the unmount method (a second concurrent user of
     * the connection) nor {@code close()} (returns a possibly-live connection to the pool) is safe;
     * {@code Connection.abort} is the JDBC primitive designed for exactly this. {@code synchronized}
     * because a straggler worker's self-abort and the dispatch thread's {@code releaseAll} can race
     * on the same instance; {@code release()} needs no synchronization (release and abort never
     * target the same instance: the carrier's per-key remove arbitrates which path processes an
     * entry, and release is only chosen for keys whose worker has settled).
     */
    private static MethodSpec abortMethod() {
        return MethodSpec.methodBuilder("abort")
            .addModifiers(Modifier.PUBLIC, Modifier.SYNCHRONIZED)
            .returns(void.class)
            .beginControlFlow("if (released)")
            .addStatement("return")
            .endControlFlow()
            .addStatement("released = true")
            .addStatement("evict(connection, abortExecutor)")
            .addJavadoc("Evicts the connection without running the unmount method, for a connection whose\n"
                + "worker may still be executing on it (a scatter straggler past the join deadline). The\n"
                + "identity cannot be proven unmounted and the connection cannot be proven idle, so it is\n"
                + "aborted and never returned to the pool; the straggler's eventual completion lands\n"
                + "harmlessly on the dead connection. Idempotent, and safe against a concurrent abort.\n")
            .build();
    }

    /**
     * The application-scoped runtime holding the sources: a default ({@code DataSource}, dialect,
     * jOOQ {@code Settings}) triple plus a per-tenant map of them for database-per-tenant routing.
     * The triple is what a context needs to render the consumer's SQL correctly (schema and render
     * mapping above all), so no {@code DSLContext} graphitron produces is missing the consumer's
     * jOOQ configuration; {@code Settings} is exactly the part of a {@code Configuration} that is
     * safe to accept, since what graphitron must own (the connection provider, the transaction
     * provider) is not in it. The plain {@code DataSource}-and-dialect constructor forms stay as
     * delegating conveniences wrapping default settings, so no consumer call site breaks.
     * Multi-tenant builds additionally carry the fan-out execution configuration (the bounded
     * scatter executor and the scatter deadline); deployment-time values, so they never touch the
     * Mojo. No hook state lives here: the mount/unmount choice is emitted, not held. The held
     * callbacks are the consumer's optional {@code FetcherErrorLog} and {@code FetcherErrorResponse},
     * each set through a wither rather than a constructor parameter so they compose with every
     * constructor form instead of doubling them.
     */
    private static TypeSpec runtime(ClassName runtime, ClassName pinnedConnection, ClassName instrumentation,
                                    ClassName fetcherErrorLog, ClassName fetcherErrorResponse,
                                    SessionHooks sessionHooks, TypeName tenantKey,
                                    boolean multiTenant, ClassName tenantConnections) {
        var payload = payloadParams(sessionHooks);
        var source = runtime.nestedClass(SOURCE_CLASS_NAME);

        var defaultSourceField = FieldSpec.builder(source, "defaultSource", Modifier.PRIVATE, Modifier.FINAL).build();
        var tenantSourcesField = FieldSpec.builder(
                ParameterizedTypeName.get(MAP, tenantKey, source),
                "sourcesByTenant", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("Per-tenant sources for database-per-tenant routing; empty for the single-tenant\n"
                + "runtime. Keyed by the divined tenant value; the key type is read off the catalog's\n"
                + "tenant column when {@code <tenantColumn>} is configured, {@code Object} otherwise\n"
                + "(the key type is a classification concern, not the lifecycle's). Each entry carries\n"
                + "its own dialect, so a database-per-tenant deployment may mix dialects.\n")
            .build();
        // Same-thread executor for Connection.abort(); the abort work is trivial and must complete before
        // the borrow returns, so there is no reason to hand it to another thread.
        var abortExecutorField = FieldSpec.builder(EXECUTOR, "abortExecutor", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("$T::run", Runnable.class)
            .build();
        // Fan-out execution configuration (multi-tenant builds only): the bounded scatter executor
        // and the scatter deadline.
        var defaultFanOutConcurrencyField = FieldSpec.builder(int.class, "DEFAULT_FAN_OUT_CONCURRENCY",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("8")
            .addJavadoc("Default scatter concurrency cap: workers in flight on the runtime's one bounded\n"
                + "pool, shared by all requests.\n")
            .build();
        var defaultFanOutTimeoutField = FieldSpec.builder(DURATION, "DEFAULT_FAN_OUT_TIMEOUT",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.ofSeconds(10)", DURATION)
            .addJavadoc("Default scatter deadline per fanned field.\n")
            .build();
        var fanOutExecutorField = FieldSpec.builder(EXECUTOR, "fanOutExecutor", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The bounded executor scatter workers run on. Independent of {@link #abortExecutor}\n"
                + "(same-thread, for {@code Connection.abort} only); the two are never conflated.\n")
            .build();
        var fanOutTimeoutField = FieldSpec.builder(DURATION, "fanOutTimeout", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The per-scatter deadline, enforced by the scatter join whichever executor runs the\n"
                + "workers.\n")
            .build();
        var fetcherErrorLogField = FieldSpec.builder(fetcherErrorLog, "fetcherErrorLog", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The consumer's log for fetcher exceptions, or {@code null} for Graphitron's default\n"
                + "line. Application-scoped like the sources; set by {@link #withFetcherErrorLog}.\n")
            .build();
        var fetcherErrorResponseField = FieldSpec.builder(fetcherErrorResponse, "fetcherErrorResponse",
                Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The consumer's choice of what the client reads for a fetcher exception, or\n"
                + "{@code null} for Graphitron's default text. Application-scoped like the sources; set by\n"
                + "{@link #withFetcherErrorResponse}.\n")
            .build();

        var sourceMapParamType = ParameterizedTypeName.get(MAP, WildcardTypeName.subtypeOf(tenantKey), source);
        var dataSourceMapParamType = ParameterizedTypeName.get(MAP, WildcardTypeName.subtypeOf(tenantKey), DATA_SOURCE);

        // Canonical constructor: a default source plus the per-tenant source map; every other
        // constructor form delegates here.
        var canonicalBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(source, "defaultSource")
            .addParameter(sourceMapParamType, "sourcesByTenant");
        if (multiTenant) {
            canonicalBuilder
                .addParameter(EXECUTOR, "fanOutExecutor")
                .addParameter(DURATION, "fanOutTimeout");
        }
        canonicalBuilder
            .addStatement("this.defaultSource = $T.requireNonNull(defaultSource, $S)", OBJECTS, "defaultSource");
        if (multiTenant) {
            // The consumer's keys take the column's form here, so every comparison downstream
            // (the acquisition gate, the fan-out domain, the source lookup) is like with like.
            canonicalBuilder
                .addStatement("$T<$T, $T> canonical = new $T<>()", MAP, tenantKey, source, LINKED_HASH_MAP)
                .addStatement("$T<$T, Object> spelledAs = new $T<>()", MAP, tenantKey, HASH_MAP)
                .beginControlFlow("for ($T<? extends $T, $T> entry :"
                    + " $T.requireNonNull(sourcesByTenant, $S).entrySet())",
                    ClassName.get("java.util", "Map", "Entry"), tenantKey, source, OBJECTS, "sourcesByTenant")
                .addStatement("$T key = $T.canonicalTenantKey($T.requireNonNull(entry.getKey(), $S))",
                    tenantKey, tenantConnections, OBJECTS, "tenant key")
                .addStatement("Object earlier = spelledAs.putIfAbsent(key, entry.getKey())")
                .beginControlFlow("if (earlier != null)")
                .addStatement("throw new $T($S + earlier + $S + entry.getKey() + $S + key + $S)",
                    IllegalArgumentException.class, "Tenant keys '", "' and '",
                    "' name the same tenant '", "': the tenant column stores them as one value.")
                .endControlFlow()
                .addStatement("canonical.put(key, entry.getValue())")
                .endControlFlow()
                .addStatement("this.sourcesByTenant = canonical");
        } else {
            canonicalBuilder
                .addStatement("this.sourcesByTenant = new $T<>($T.requireNonNull(sourcesByTenant, $S))",
                    LINKED_HASH_MAP, OBJECTS, "sourcesByTenant");
        }
        if (multiTenant) {
            canonicalBuilder
                .addStatement("this.fanOutExecutor = $T.requireNonNull(fanOutExecutor, $S)", OBJECTS, "fanOutExecutor")
                .addStatement("this.fanOutTimeout = $T.requireNonNull(fanOutTimeout, $S)", OBJECTS, "fanOutTimeout")
                .beginControlFlow("if (fanOutTimeout.isZero() || fanOutTimeout.isNegative())")
                .addStatement("throw new $T($S + fanOutTimeout)", IllegalArgumentException.class,
                    "fanOutTimeout must be positive, got: ")
                .endControlFlow();
        }
        canonicalBuilder.addStatement("this.fetcherErrorLog = null")
            .addStatement("this.fetcherErrorResponse = null");

        // The wither's copy constructor: every field as-is, so a multi-tenant runtime's fan-out
        // executor is shared rather than a second pool created, and the source map is not
        // re-copied (neither runtime ever mutates it).
        var copyBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .addParameter(source, "defaultSource")
            .addParameter(ParameterizedTypeName.get(MAP, tenantKey, source), "sourcesByTenant");
        if (multiTenant) {
            copyBuilder
                .addParameter(EXECUTOR, "fanOutExecutor")
                .addParameter(DURATION, "fanOutTimeout");
        }
        copyBuilder.addParameter(fetcherErrorLog, "fetcherErrorLog")
            .addParameter(fetcherErrorResponse, "fetcherErrorResponse")
            .addStatement("this.defaultSource = defaultSource")
            .addStatement("this.sourcesByTenant = sourcesByTenant");
        if (multiTenant) {
            copyBuilder
                .addStatement("this.fanOutExecutor = fanOutExecutor")
                .addStatement("this.fanOutTimeout = fanOutTimeout");
        }
        var copyConstructor = copyBuilder
            .addStatement("this.fetcherErrorLog = fetcherErrorLog")
            .addStatement("this.fetcherErrorResponse = fetcherErrorResponse")
            .build();

        var withFetcherErrorLog = MethodSpec.methodBuilder("withFetcherErrorLog")
            .addModifiers(Modifier.PUBLIC)
            .returns(runtime)
            .addParameter(fetcherErrorLog, "log")
            .addStatement("return new $T(defaultSource, sourcesByTenant$L, $T.requireNonNull(log, $S),"
                    + " fetcherErrorResponse)",
                runtime, multiTenant ? ", fanOutExecutor, fanOutTimeout" : "", OBJECTS, "log")
            .addJavadoc("A copy of this runtime that hands every fetcher exception no {@code @error} mapping\n"
                + "claims to {@code log} instead of Graphitron's own ERROR line, and every exception a\n"
                + "handler caught to its {@code matched}. The client's response does not change. The copy\n"
                + "shares this runtime's sources"
                + (multiTenant ? " and fan-out executor" : "") + " and its {@code FetcherErrorResponse};\n"
                + "this runtime is unchanged.\n"
                + "@param log the consumer's log; must not be {@code null}\n"
                + "@return a runtime carrying {@code log}\n")
            .build();
        var withFetcherErrorResponse = MethodSpec.methodBuilder("withFetcherErrorResponse")
            .addModifiers(Modifier.PUBLIC)
            .returns(runtime)
            .addParameter(fetcherErrorResponse, "response")
            .addStatement("return new $T(defaultSource, sourcesByTenant$L, fetcherErrorLog,"
                    + " $T.requireNonNull(response, $S))",
                runtime, multiTenant ? ", fanOutExecutor, fanOutTimeout" : "", OBJECTS, "response")
            .addJavadoc("A copy of this runtime that asks {@code response} for the reference of every fetcher\n"
                + "exception, the {@code message:} of every error a handler caught and the top-level message\n"
                + "of every error none caught. The copy shares this runtime's sources"
                + (multiTenant ? " and fan-out executor" : "") + " and its\n"
                + "{@code FetcherErrorLog}; this runtime is unchanged.\n"
                + "@param response the consumer's response; must not be {@code null}\n"
                + "@return a runtime carrying {@code response}\n")
            .build();
        var fetcherErrorLogAccessor = MethodSpec.methodBuilder("fetcherErrorLog")
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(OPTIONAL, fetcherErrorLog))
            .addStatement("return $T.ofNullable(fetcherErrorLog)", OPTIONAL)
            .addJavadoc("The log set by {@link #withFetcherErrorLog}, or empty for Graphitron's default line.\n")
            .build();
        var fetcherErrorResponseAccessor = MethodSpec.methodBuilder("fetcherErrorResponse")
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(OPTIONAL, fetcherErrorResponse))
            .addStatement("return $T.ofNullable(fetcherErrorResponse)", OPTIONAL)
            .addJavadoc("The response set by {@link #withFetcherErrorResponse}, or empty for Graphitron's\n"
                + "default text.\n")
            .build();
        var canonicalConstructor = canonicalBuilder
            .addJavadoc("Builds the runtime over a default source (untenanted / single-tenant SQL) and a\n"
                + "per-tenant source map for database-per-tenant routing. Each {@link Source} carries its\n"
                + "own {@code DataSource}, dialect, and jOOQ {@code Settings}, so a consumer's schema or\n"
                + "render mapping reaches every context graphitron mints. The consumer (or their\n"
                + "framework) still owns pool creation and tuning.\n"
                + "@param defaultSource source for untenanted SQL; must not be {@code null}\n"
                + "@param sourcesByTenant per-tenant sources keyed by divined tenant value; may be empty\n"
                + (multiTenant
                    ? "Each key is reduced to the tenant column's form ({@link "
                        + tenantConnections.simpleName() + "#canonicalTenantKey}), so\n"
                        + "{@link #tenantKeys()} and the mount report that form whichever spelling was supplied.\n"
                        + "@param fanOutExecutor the executor scatter workers run on; the supplier owns its\n"
                        + "concurrency bound (e.g. virtual threads); must not be {@code null}\n"
                        + "@param fanOutTimeout the per-scatter deadline, enforced by the join whichever\n"
                        + "executor runs the workers; must be positive\n"
                        + "@throws IllegalArgumentException when two keys name the same tenant, or a key is not\n"
                        + "a value of the tenant column\n"
                    : ""))
            .build();

        var sourceOnlyDelegating = multiTenant
            ? MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(source, "defaultSource")
                .addParameter(sourceMapParamType, "sourcesByTenant")
                .addStatement("this(defaultSource, sourcesByTenant,"
                    + " boundedFanOutPool(DEFAULT_FAN_OUT_CONCURRENCY), DEFAULT_FAN_OUT_TIMEOUT)")
                .addJavadoc("Source-form constructor with the default fan-out configuration.\n")
                .build()
            : null;

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(DATA_SOURCE, "dataSource")
            .addParameter(SQL_DIALECT, "dialect")
            .addStatement(multiTenant
                    ? "this(new $T(dataSource, dialect, null), $T.of(),"
                        + " boundedFanOutPool(DEFAULT_FAN_OUT_CONCURRENCY), DEFAULT_FAN_OUT_TIMEOUT)"
                    : "this(new $T(dataSource, dialect, null), $T.of())",
                source, MAP)
            .addJavadoc("Builds the single-source runtime over one consumer-owned {@code DataSource} and\n"
                + "dialect with default jOOQ {@code Settings} (no per-tenant routing). Delegating\n"
                + "convenience over the {@link Source}-form constructor.\n"
                + "@param dataSource the consumer's pooled {@code DataSource}; must not be {@code null}\n"
                + "@param dialect the jOOQ {@code SQLDialect} for this database; must not be {@code null}\n")
            .build();

        var dataSourceMapConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(DATA_SOURCE, "defaultDataSource")
            .addParameter(dataSourceMapParamType, "dataSourcesByTenant")
            .addParameter(SQL_DIALECT, "dialect")
            .addStatement(multiTenant
                    ? CodeBlock.of("this(defaultDataSource, dataSourcesByTenant, dialect, DEFAULT_FAN_OUT_CONCURRENCY,"
                        + " DEFAULT_FAN_OUT_TIMEOUT)")
                    : CodeBlock.of("this(new $T(defaultDataSource, dialect, null),"
                        + " wrapSources(dataSourcesByTenant, dialect))", source))
            .addJavadoc("Builds the tenant-routing runtime from bare {@code DataSource}s sharing one dialect\n"
                + "and default jOOQ {@code Settings}. Delegating convenience over the {@link Source} form"
                + (multiTenant ? ",\nwith the default fan-out configuration" : "") + ".\n"
                + "@param defaultDataSource source for untenanted SQL; must not be {@code null}\n"
                + "@param dataSourcesByTenant per-tenant sources keyed by divined tenant value; may be empty\n"
                + "@param dialect the jOOQ {@code SQLDialect} shared by every source here; must not be {@code null}\n")
            .build();

        var cappedConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(DATA_SOURCE, "defaultDataSource")
            .addParameter(dataSourceMapParamType, "dataSourcesByTenant")
            .addParameter(SQL_DIALECT, "dialect")
            .addParameter(int.class, "fanOutConcurrency")
            .addParameter(DURATION, "fanOutTimeout")
            .addStatement("this(new $T(defaultDataSource, dialect, null),"
                + " wrapSources(dataSourcesByTenant, dialect),"
                + " boundedFanOutPool(fanOutConcurrency), fanOutTimeout)", source)
            .addJavadoc("Builds the tenant-routing runtime with an explicit fan-out cap and deadline; the\n"
                + "runtime owns a bounded pool of platform threads sized by the cap. To own threading\n"
                + "yourself (e.g. virtual threads), use the {@code Executor}-form constructor instead.\n"
                + "@param defaultDataSource source for untenanted SQL; must not be {@code null}\n"
                + "@param dataSourcesByTenant per-tenant sources keyed by divined tenant value; may be empty\n"
                + "@param dialect the jOOQ {@code SQLDialect} shared by every source here; must not be {@code null}\n"
                + "@param fanOutConcurrency the maximum scatter workers in flight; at least 1\n"
                + "@param fanOutTimeout the per-scatter deadline; must be positive\n")
            .build();

        var executorFormConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(DATA_SOURCE, "defaultDataSource")
            .addParameter(dataSourceMapParamType, "dataSourcesByTenant")
            .addParameter(SQL_DIALECT, "dialect")
            .addParameter(EXECUTOR, "fanOutExecutor")
            .addParameter(DURATION, "fanOutTimeout")
            .addStatement("this(new $T(defaultDataSource, dialect, null),"
                + " wrapSources(dataSourcesByTenant, dialect), fanOutExecutor, fanOutTimeout)", source)
            .addJavadoc("Builds the tenant-routing runtime from bare {@code DataSource}s with an explicit\n"
                + "scatter executor and deadline. Delegating convenience over the {@link Source} form.\n"
                + "@param defaultDataSource source for untenanted SQL; must not be {@code null}\n"
                + "@param dataSourcesByTenant per-tenant sources keyed by divined tenant value; may be empty\n"
                + "@param dialect the jOOQ {@code SQLDialect} shared by every source here; must not be {@code null}\n"
                + "@param fanOutExecutor the executor scatter workers run on; must not be {@code null}\n"
                + "@param fanOutTimeout the per-scatter deadline; must be positive\n")
            .build();

        var wrapSources = MethodSpec.methodBuilder("wrapSources")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(MAP, tenantKey, source))
            .addParameter(dataSourceMapParamType, "dataSourcesByTenant")
            .addParameter(SQL_DIALECT, "dialect")
            .addStatement("$T<$T, $T> sources = new $T<>()", MAP, tenantKey, source, LINKED_HASH_MAP)
            .beginControlFlow("for ($T<? extends $T, $T> entry :"
                + " $T.requireNonNull(dataSourcesByTenant, $S).entrySet())",
                ClassName.get("java.util", "Map", "Entry"), tenantKey, DATA_SOURCE, OBJECTS, "dataSourcesByTenant")
            .addStatement("sources.put(entry.getKey(), new $T(entry.getValue(), dialect, null))", source)
            .endControlFlow()
            .addStatement("return sources")
            .addJavadoc("Wraps a bare per-tenant {@code DataSource} map into sources sharing one dialect and\n"
                + "default settings, preserving the configured key order.\n")
            .build();

        var boundedFanOutPool = MethodSpec.methodBuilder("boundedFanOutPool")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(EXECUTOR)
            .addParameter(int.class, "fanOutConcurrency")
            .beginControlFlow("if (fanOutConcurrency < 1)")
            .addStatement("throw new $T($S + fanOutConcurrency)", IllegalArgumentException.class,
                "fanOutConcurrency must be at least 1, got: ")
            .endControlFlow()
            .addStatement("$T names = new $T()", ATOMIC_INTEGER, ATOMIC_INTEGER)
            .addCode("return $T.newFixedThreadPool(fanOutConcurrency, task -> {\n", EXECUTORS)
            .addCode("    $T thread = new $T(task, $S + names.incrementAndGet());\n",
                Thread.class, Thread.class, "graphitron-fanout-")
            .addCode("    thread.setDaemon(true);\n")
            .addCode("    return thread;\n")
            .addCode("});\n")
            .addJavadoc("The default scatter executor: a fixed pool of daemon platform threads sized by the\n"
                + "cap (generated output targets Java 17, so no virtual threads here; a consumer on a newer\n"
                + "JVM supplies its own {@code Executor} instead). Daemon threads, so an application\n"
                + "shutdown is never held open by idle fan-out workers.\n")
            .build();
        var fanOutExecutorAccessor = MethodSpec.methodBuilder("fanOutExecutor")
            .addModifiers(Modifier.PUBLIC)
            .returns(EXECUTOR)
            .addStatement("return fanOutExecutor")
            .addJavadoc("The executor scatter workers run on.\n")
            .build();
        var fanOutTimeoutAccessor = MethodSpec.methodBuilder("fanOutTimeout")
            .addModifiers(Modifier.PUBLIC)
            .returns(DURATION)
            .addStatement("return fanOutTimeout")
            .addJavadoc("The per-scatter deadline, enforced by the scatter join.\n")
            .build();
        var tenantKeysAccessor = MethodSpec.methodBuilder("tenantKeys")
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(SET, tenantKey))
            .addStatement("return $T.unmodifiableSet(sourcesByTenant.keySet())",
                ClassName.get("java.util", "Collections"))
            .addJavadoc("The configured tenant keys in the map's configured order (the constructor copies\n"
                + "into a {@code LinkedHashMap}), so the fan-out domain's concatenation order is\n"
                + "deployment-stable. Read-only view: a live {@code keySet()} would let a caller\n"
                + "remove tenants from the routing map through it.\n")
            .build();

        var dialectAccessor = MethodSpec.methodBuilder("dialect")
            .addModifiers(Modifier.PUBLIC)
            .returns(SQL_DIALECT)
            .addStatement("return defaultSource.dialect()")
            .addJavadoc("The default source's jOOQ {@code SQLDialect}. A per-tenant source carries its own\n"
                + "dialect; contexts graphitron mints read the resolved entry's source, not this accessor.\n")
            .build();

        var acquireBuilder = MethodSpec.methodBuilder("acquire")
            .addModifiers(Modifier.PUBLIC)
            .returns(pinnedConnection);
        var acquireForTenantBuilder = MethodSpec.methodBuilder("acquireForTenant")
            .addModifiers(Modifier.PUBLIC)
            .returns(pinnedConnection)
            .addParameter(tenantKey, "tenantKey");
        var payloadArgs = new StringBuilder();
        for (var p : payload) {
            acquireBuilder.addParameter(p.javaType(), p.name());
            acquireForTenantBuilder.addParameter(p.javaType(), p.name());
            payloadArgs.append(", ").append(p.name());
        }
        // Multi-tenant builds hand the mount the tenant it is mounting for: none for the default
        // source, the routed key for a tenant source.
        CodeBlock defaultTenantArg = multiTenant ? CodeBlock.of(", $T.empty()", OPTIONAL) : CodeBlock.of("");
        CodeBlock keyedTenantArg = multiTenant ? CodeBlock.of(", $T.of(tenantKey)", OPTIONAL) : CodeBlock.of("");
        var acquire = acquireBuilder
            .addException(SQL_EXCEPTION)
            .addStatement("return $T.acquire(defaultSource.dataSource(), defaultSource.dialect(),"
                + " defaultSource.settings()$L, abortExecutor$L)", pinnedConnection, defaultTenantArg,
                payloadArgs.toString())
            .addJavadoc("Pins one connection from the default source"
                + (payload.isEmpty() ? "" : " and mounts identity on it from the typed\npayload")
                + ". Fail-closed. The caller releases the returned\n"
                + "{@code PinnedConnection} exactly once at operation completion; the per-operation\n"
                + "carrier wired by the execution instrumentation does this, so consumers register\n"
                + "nothing.\n")
            .build();

        var acquireForTenant = acquireForTenantBuilder
            .addException(SQL_EXCEPTION)
            .addStatement("$T tenantSource = sourcesByTenant.get(tenantKey)", source)
            .beginControlFlow("if (tenantSource == null)")
            .addComment("Unknown divined tenant: a request-level error before any SQL. Distinct from the")
            .addComment("acquisition-failure family so callers can map it structurally, not by message.")
            .addStatement("throw new $T($S + tenantKey)", NO_SUCH_ELEMENT, "No source configured for tenant key: ")
            .endControlFlow()
            .addStatement("return $T.acquire(tenantSource.dataSource(), tenantSource.dialect(),"
                + " tenantSource.settings()$L, abortExecutor$L)", pinnedConnection, keyedTenantArg,
                payloadArgs.toString())
            .addJavadoc("Pins one connection from the {@code tenantKey}'s source"
                + (payload.isEmpty() ? "" : " and mounts identity on it")
                + ", for\ndatabase-per-tenant routing. An unknown key raises\n"
                + "{@link java.util.NoSuchElementException} before any connection is acquired (request-level\n"
                + "error, no SQL). Per-key deduplication within one operation is the caller's ({@code $L}); this\n"
                + "is the raw keyed acquisition primitive.\n"
                + "@param tenantKey the divined tenant value selecting the source\n", TENANT_CONNECTIONS_CLASS_NAME)
            .build();

        var graphQL = ClassName.get("graphql", "GraphQL");
        var graphQLBuilder = ClassName.get("graphql", "GraphQL", "Builder");
        var graphQLSchema = ClassName.get("graphql.schema", "GraphQLSchema");
        var newGraphQL = MethodSpec.methodBuilder("newGraphQL")
            .addModifiers(Modifier.PUBLIC)
            .returns(graphQLBuilder)
            .addParameter(graphQLSchema, "schema")
            .addStatement("return $T.newGraphQL(schema).instrumentation(new $T(this))", graphQL, instrumentation)
            .addJavadoc("Builds a {@link graphql.GraphQL.Builder} over {@code schema} with the connection-lifecycle\n"
                + "instrumentation already attached: every operation pins connections on first demand, mounts\n"
                + "identity, runs in operation-typed transactions, and releases at completion, with no\n"
                + "registration by the consumer. This is the owned-connection engine assembly; pair it with\n"
                + "{@code Graphitron.buildSchema(...)}: {@code var engine = runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();}.\n"
                + "\n"
                + "<p>A later {@code .instrumentation(other)} on the returned builder replaces the attached\n"
                + "instrumentation, after which nothing is pinned or mounted; add your own (for example\n"
                + "OpenTelemetry) through a {@code ChainedInstrumentation} that keeps\n"
                + "{@code new GraphitronConnectionInstrumentation(this)} in the chain.\n"
                + (sessionHooks.emitsHookImplementation()
                    ? "\n<p>When the mount rejects the caller's identity, {@code execute} throws\n"
                        + "{@code SessionMountException} in place of a result, with the mount's exception as\n"
                        + "its cause.\n"
                    : "")
                + "\n"
                + "<p>The escape-hatch engine ({@code Graphitron.newGraphQL()}) attaches no instrumentation;\n"
                + "there the caller owns the {@code DSLContext}, transactions, and identity.\n"
                + "@param schema the {@link graphql.schema.GraphQLSchema} from {@code Graphitron.buildSchema(...)}\n"
                + "@return a builder with the owned-connection instrumentation attached, ready for {@code .build()}\n")
            .build();

        var builder = TypeSpec.classBuilder(RUNTIME_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Application-scoped runtime that owns the connection lifecycle: built once at wiring\n"
                + "time via {@code Graphitron.runtime(dataSource, dialect)} (or a {@link Source}-form\n"
                + "constructor carrying jOOQ {@code Settings}), it pins connections on first demand, mounts\n"
                + "and unmounts per-request identity through the generated hook's static methods, and\n"
                + "demarcates operation-typed transactions (via the instrumentation {@link #newGraphQL}\n"
                + "attaches). Holds no per-request state and no hook state: the mount choice is emitted\n"
                + "code, never a held value. The callbacks it holds are the consumer's application-scoped\n"
                + "{@code FetcherErrorLog} ({@link #withFetcherErrorLog}) and {@code FetcherErrorResponse}\n"
                + "({@link #withFetcherErrorResponse}).\n");
        if (multiTenant) {
            builder.addField(defaultFanOutConcurrencyField)
                .addField(defaultFanOutTimeoutField);
        }
        builder.addField(defaultSourceField)
            .addField(tenantSourcesField)
            .addField(abortExecutorField);
        if (multiTenant) {
            builder.addField(fanOutExecutorField)
                .addField(fanOutTimeoutField);
        }
        builder.addField(fetcherErrorLogField)
            .addField(fetcherErrorResponseField);
        builder.addMethod(canonicalConstructor)
            .addMethod(copyConstructor);
        if (multiTenant) {
            builder.addMethod(sourceOnlyDelegating)
                .addMethod(executorFormConstructor)
                .addMethod(cappedConstructor)
                .addMethod(dataSourceMapConstructor);
        } else {
            builder.addMethod(dataSourceMapConstructor);
        }
        builder.addMethod(constructor)
            .addMethod(dialectAccessor)
            .addMethod(acquire)
            .addMethod(acquireForTenant)
            .addMethod(newGraphQL)
            .addMethod(withFetcherErrorLog)
            .addMethod(fetcherErrorLogAccessor)
            .addMethod(withFetcherErrorResponse)
            .addMethod(fetcherErrorResponseAccessor)
            .addMethod(wrapSources);
        if (multiTenant) {
            builder.addMethod(fanOutExecutorAccessor)
                .addMethod(fanOutTimeoutAccessor)
                .addMethod(tenantKeysAccessor)
                .addMethod(boundedFanOutPool);
        }
        builder.addType(sourceType(source));
        return builder.build();
    }

    /**
     * The runtime's nested {@code Source}: one pool graphitron can check a connection out of,
     * with the dialect and jOOQ {@code Settings} every context minted over that pool renders
     * through. A future role axis (e.g. an optional read pool routed by operation type) becomes
     * part of the routing map's key, never a second field here, so the value type is not
     * rewritten when it arrives.
     */
    private static TypeSpec sourceType(ClassName source) {
        return TypeSpec.classBuilder(SOURCE_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("One connection source: a consumer-owned pooled {@code DataSource}, its jOOQ\n"
                + "dialect, and the jOOQ {@code Settings} (schema and render mapping above all) every\n"
                + "context minted over it renders through, the consumer's own mount method included.\n"
                + "{@code Settings} is the part of a jOOQ {@code Configuration} that is safe to accept\n"
                + "here; the providers graphitron must own are not in it.\n")
            .addField(FieldSpec.builder(DATA_SOURCE, "dataSource", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(SQL_DIALECT, "dialect", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(SETTINGS, "settings", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(DATA_SOURCE, "dataSource")
                .addParameter(SQL_DIALECT, "dialect")
                .addParameter(SETTINGS, "settings")
                .addStatement("this.dataSource = $T.requireNonNull(dataSource, $S)", OBJECTS, "dataSource")
                .addStatement("this.dialect = $T.requireNonNull(dialect, $S)", OBJECTS, "dialect")
                .addStatement("this.settings = settings == null ? new $T() : settings", SETTINGS)
                .addJavadoc("@param dataSource the consumer's pooled {@code DataSource}; must not be {@code null}\n"
                    + "@param dialect the jOOQ {@code SQLDialect} for this source; must not be {@code null}\n"
                    + "@param settings the jOOQ {@code Settings} for this source; {@code null} means defaults\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("dataSource")
                .addModifiers(Modifier.PUBLIC)
                .returns(DATA_SOURCE)
                .addStatement("return dataSource")
                .addJavadoc("The pool connections are checked out of.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("dialect")
                .addModifiers(Modifier.PUBLIC)
                .returns(SQL_DIALECT)
                .addStatement("return dialect")
                .addJavadoc("The jOOQ dialect for this source.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("settings")
                .addModifiers(Modifier.PUBLIC)
                .returns(SETTINGS)
                .addStatement("return settings")
                .addJavadoc("The jOOQ {@code Settings} for this source; never {@code null}.\n")
                .build())
            .build();
    }

    /**
     * The per-operation connection carrier, unified across both topologies with single-tenant as
     * the one-key case: one entry per <em>distinct</em> key within an operation, keyed by
     * {@code Optional<tenantKey>} with {@code Optional.empty()} as the default source (the map is
     * keyed by the typed divined tenant value, and no tenant value means the default source;
     * {@code Optional} is also what sidesteps {@code ConcurrentHashMap}'s no-null-keys rule).
     * An entry holds the key's pinned connection and its provider-bound {@code DSLContext}
     * together, minted inside the one {@code computeIfAbsent} and discarded together at release:
     * one cached {@code DSLContext} per key is one transaction provider per pinned connection,
     * so the provider's scope stack is the only record of open transactions on that connection, and the
     * mount's handle (written once to the entry's {@code Configuration}) lives and dies with the
     * connection it describes.
     *
     * <p>Acquisition is lazy on every path: {@code dslFor(key)} / {@code dslDefault()} pin and
     * mount on first demand and reuse thereafter, so an operation that touches no database never
     * pins and never mounts. {@code releaseAll()} releases every entry on every completion path,
     * per-connection eviction on unmount failure, idempotent.
     *
     * <p>The {@code DSL.using(...) + TransactionProvider} binding lives here, single-sourced, so
     * the many per-field routing sites consume {@code dslFor(key)} / {@code getDslContext(env)}
     * and never re-emit the binding (only <em>which key</em> and <em>where it routes</em> are
     * schema-shaped). The payload is retained once, by this carrier: lazy acquisition means any
     * fetcher may trigger the mount for a key, so the carrier holds the payload contextArguments
     * for the life of the request and hands them to {@code acquire} on each mint.
     */
    private static TypeSpec tenantConnections(ClassName self, ClassName runtime, ClassName pinnedConnection,
                                              ClassName provider, ClassName commitPolicy, TypeName tenantKey,
                                              boolean multiTenant, SessionHooks sessionHooks,
                                              ClassName requestTenants, CodeBlock tenantColumn,
                                              ClassName errorRouter, ClassName columnValues,
                                              ClassName sessionMountException) {
        var payload = payloadParams(sessionHooks);
        boolean mounts = sessionHooks.emitsHookImplementation();
        TypeName handleType = sessionHooks instanceof SessionHooks.Handled h ? h.handleType() : null;
        boolean handled = handleType != null;
        var clientException = ClassName.get(self.packageName(),
            no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.CLASS_NAME);
        var optionalKey = ParameterizedTypeName.get(ClassName.get("java.util", "Optional"), tenantKey);
        var entryType = self.nestedClass("Entry");
        var entryMapType = ParameterizedTypeName.get(MAP, optionalKey, entryType);

        var runtimeField = FieldSpec.builder(runtime, "runtime", Modifier.PRIVATE, Modifier.FINAL).build();
        var policyField = FieldSpec.builder(commitPolicy, "commitPolicy", Modifier.PRIVATE, Modifier.FINAL).build();
        var entriesField = FieldSpec.builder(entryMapType, "entries", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("new $T<>()", CONCURRENT_HASH_MAP)
            .addJavadoc("Concurrent in both topologies, with per-key single acquisition\n"
                + "({@code computeIfAbsent}, the only minting mechanism in the carrier): "
                + (multiTenant
                    ? "under fan-out,\nscatter partitions distinct keys one worker each but nothing"
                        + " structural prevents a\nworker and the dispatch thread racing the same key,"
                        + " so one-pin-per-key is this map's\ncontract, not an accident of the callers"
                    : "under lazy\nacquisition, serial dispatch is an execution-strategy assumption"
                        + " rather than a\nstructural guarantee, and the concurrent map is cheaper than"
                        + " defending it")
                + ".\n")
            .build();
        var timedOutField = FieldSpec.builder(ParameterizedTypeName.get(SET, tenantKey), "timedOutTenants",
                Modifier.PRIVATE, Modifier.FINAL)
            .initializer("$T.newKeySet()", CONCURRENT_HASH_MAP)
            .addJavadoc("Tenant keys whose scatter worker missed the join deadline. A {@code TimedOut} outcome\n"
                + "means the join stopped waiting, not that the worker stopped working, so a timed-out\n"
                + "key's pinned connection may still be executing: {@link #dslFor} never hands it out\n"
                + "again, and {@link #releaseAll} routes it through {@code PinnedConnection.abort()}\n"
                + "instead of a close that would return a possibly-live connection to the pool.\n")
            .build();
        var mountFailureField = FieldSpec.builder(ParameterizedTypeName.get(ATOMIC_REFERENCE, sessionMountException),
                "firstMountFailure", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("new $T<>()", ATOMIC_REFERENCE)
            .addJavadoc("The operation's first rejected mount, set once ({@code compareAndSet}, so concurrent\n"
                + "scatter workers resolve to one winner). Every later acquisition on any key rethrows it\n"
                + "rather than retrying the mount: the payload is one identity for every key.\n")
            .build();
        var closedField = FieldSpec.builder(boolean.class, "closed", Modifier.PRIVATE, Modifier.VOLATILE)
            .addJavadoc("Set by {@link #releaseAll} before draining, so a straggler worker that finishes\n"
                + "pinning after the operation completed aborts its own connection instead of leaking it.\n")
            .build();
        var scatterWorkerMarkerField = FieldSpec.builder(
                ParameterizedTypeName.get(THREAD_LOCAL, ClassName.get(Boolean.class)), "SCATTER_WORKER",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("new $T<>()", THREAD_LOCAL)
            .addJavadoc("Marks scatter worker threads for the re-entrancy guard: a {@code perTenant} body\n"
                + "calling {@link #scatter} would make a bounded pool wait on itself and deadlock, so the\n"
                + "violation throws immediately instead.\n")
            .build();

        var tenantSetType = ParameterizedTypeName.get(SET, tenantKey);
        var tenantsField = FieldSpec.builder(tenantSetType, "tenants", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The request tenant set: the tenants this request may touch in this deployment,\n"
                + "decoded once from the factory's parameter by the instrumentation. Every keyed\n"
                + "acquisition is checked against it in {@link #entryFor} before a connection is taken,\n"
                + "and it bounds {@link #fanOutDomain}, so routing and fan-out read one fact.\n")
            .build();
        var defaultTenantField = FieldSpec.builder(optionalKey, "defaultTenant", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The request's default tenant, the member of {@link #tenants} that serves global reads\n"
                + "({@link #dslGlobal()}), or empty when the request names none and global reads take the\n"
                + "default source.\n")
            .build();
        var entityDefaultTenantField = FieldSpec.builder(optionalKey, "entityDefaultTenant",
                Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The request's entity default tenant, the member of {@link #tenants} on which the\n"
                + "entity dispatcher looks up a {@code @defaultTenant} type's representations, or empty when\n"
                + "the request names none and every such representation answers {@code null}.\n")
            .build();
        var constructorBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(runtime, "runtime")
            .addParameter(commitPolicy, "commitPolicy");
        if (multiTenant) {
            constructorBuilder.addParameter(requestTenants, "requestTenants");
        }
        for (var p : payload) {
            constructorBuilder.addParameter(p.javaType(), p.name());
        }
        constructorBuilder
            .addStatement("this.runtime = runtime")
            .addStatement("this.commitPolicy = commitPolicy");
        if (multiTenant) {
            constructorBuilder
                .addStatement("$T.requireNonNull(requestTenants, $S)", OBJECTS, "requestTenants")
                .addStatement("this.tenants = requestTenants.tenants()")
                .addStatement("this.defaultTenant = requestTenants.defaultTenant()")
                .addStatement("this.entityDefaultTenant = requestTenants.entityDefaultTenant()");
        }
        for (var p : payload) {
            constructorBuilder.addStatement("this.$L = $L", p.name(), p.name());
        }
        var constructor = constructorBuilder
            .addJavadoc((multiTenant
                ? "Builds a per-operation carrier over {@code runtime} for one request. One instance per\n"
                    + "operation. Concurrency is confined to {@link #scatter}'s bounded workers, each owning\n"
                    + "one keyed connection single-threaded through {@link #dslFor}, with the dispatch thread\n"
                    + "blocked on the join for the scatter's whole duration; every other access runs serially\n"
                    + "on the dispatch thread. {@code requestTenants} carries the request tenant set, the\n"
                    + "only tenants this carrier will acquire a connection for, the default tenant that\n"
                    + "serves global reads when the request names one, and the entity default tenant the\n"
                    + "entity dispatcher resolves {@code @defaultTenant} types on.\n"
                : "Builds a per-operation carrier over {@code runtime} for one request. One instance per\n"
                    + "operation; entries are pinned on first demand.\n")
                + (payload.isEmpty() ? ""
                    : "The payload parameters are held for the life of the request: any fetcher may trigger\n"
                        + "the first mount for a key, and every mount receives the same payload.\n"))
            .build();

        var payloadArgs = new StringBuilder();
        for (var p : payload) {
            payloadArgs.append(", ").append(p.name());
        }
        String acquireDefault = "runtime.acquire(" + (payload.isEmpty() ? ""
            : payloadArgs.substring(2)) + ")";
        String acquireKeyed = "runtime.acquireForTenant(key.get()" + payloadArgs + ")";

        var entryForBuilder = MethodSpec.methodBuilder("entryFor")
            .addModifiers(Modifier.PRIVATE)
            .returns(entryType)
            .addParameter(optionalKey, "key")
            .addException(SQL_EXCEPTION);
        if (multiTenant) {
            entryForBuilder
                .addComment("Authorization before anything else: only a key in the request tenant set ever")
                .addComment("reaches the entry map, and the refusal runs ahead of the runtime's hosting check,")
                .addComment("so an unauthorized caller cannot probe which tenants this deployment hosts. Every")
                .addComment("keyed acquisition funnels through here (the static and instance dslFor, and the")
                .addComment("scatter worker); nothing calls runtime.acquireForTenant directly.")
                .beginControlFlow("if (key.isPresent() && !tenants.contains(key.get()))")
                .addStatement("throw new $T($S + key.get() + $S)", clientException,
                    "Tenant '", "' is not permitted for this request.")
                .endControlFlow()
                .beginControlFlow("if (key.isPresent() && timedOutTenants.contains(key.get()))")
                .addComment("The key's scatter worker missed the join deadline; its connection may still be")
                .addComment("executing, so it is never reused within the operation.")
                .addStatement("throw new $T($S + key.get() + $S)", IllegalStateException.class,
                    "Tenant '", "' timed out earlier in this operation; its connection is never reused.")
                .endControlFlow();
        }
        if (mounts) {
            entryForBuilder
                .addComment("A rejected mount is remembered for the operation: answer every later acquisition")
                .addComment("with it, after the tenant refusals above so their order is unchanged.")
                .addStatement("$T rejected = firstMountFailure.get()", sessionMountException)
                .beginControlFlow("if (rejected != null)")
                .addStatement("throw rejected")
                .endControlFlow();
        }
        entryForBuilder
            .addStatement("$T entry", entryType)
            .beginControlFlow("try")
            .addComment("Per-key single acquisition: exactly one pin per key, the map's one minting")
            .addComment("mechanism. The checked acquisition failure tunnels out of the compute lambda")
            .addComment("unchanged. The whole entry is minted here: pin and mount, bind a DSLContext to the")
            .addComment("pinned connection through the resolved source's dialect and settings, swap in one")
            .addComment("transaction provider (one per pinned connection, so its scope stack is the only")
            .addComment("record of open transactions on that connection). jOOQ's single-connection provider treats release as a")
            .addComment("no-op, so the runtime keeps sole ownership of close/evict.")
            .addCode("entry = entries.computeIfAbsent(key, k -> {\n")
            .addCode("    try {\n")
            .addCode("        $T pinned = k.isPresent() ? $L : $L;\n", pinnedConnection, acquireKeyed
                .replace("key.get()", "k.get()"), acquireDefault)
            .addCode("        $T dsl = $T.using(pinned.connection(), pinned.dialect(), pinned.settings());\n",
                DSL_CONTEXT, DSL)
            .addCode("        dsl.configuration().set(new $T(pinned.connection(), commitPolicy));\n", provider);
        if (handled) {
            entryForBuilder.addCode("        dsl.configuration().data($S, pinned.handle());\n",
                SessionHooks.HANDLE_DATA_KEY);
        }
        entryForBuilder
            .addCode("        return new Entry(pinned, dsl);\n")
            .addCode("    } catch ($T e) {\n", SQL_EXCEPTION)
            .addCode("        throw new $T(e);\n", COMPLETION_EXCEPTION)
            .addCode("    }\n")
            .addCode("});\n");
        if (mounts) {
            entryForBuilder
                .nextControlFlow("catch ($T e)", sessionMountException)
                .addStatement("firstMountFailure.compareAndSet(null, e)")
                .addStatement("throw firstMountFailure.get()");
        }
        entryForBuilder
            .nextControlFlow("catch ($T e)", COMPLETION_EXCEPTION)
            .beginControlFlow("if (e.getCause() instanceof $T sql)", SQL_EXCEPTION)
            .addStatement("throw sql")
            .endControlFlow()
            .addStatement("throw e")
            .endControlFlow();
        if (multiTenant) {
            entryForBuilder
                .beginControlFlow("if (closed || (key.isPresent() && timedOutTenants.contains(key.get())))")
                .addComment("The operation moved on (released, or this key's join deadline passed) while the pin")
                .addComment("was in flight; never hand out a connection the release path can no longer own.")
                .addStatement("$T stale = entries.remove(key)", entryType)
                .beginControlFlow("if (stale != null)")
                .addStatement("stale.pinned.abort()")
                .endControlFlow()
                .addStatement("throw new $T($S + key + $S)", IllegalStateException.class,
                    "Key '", "' was pinned after its scatter deadline or after the operation completed.")
                .endControlFlow();
        }
        var entryFor = entryForBuilder
            .addStatement("return entry")
            .addJavadoc("Resolves (minting on first demand) the carrier entry for {@code key}:\n"
                + "{@code Optional.empty()} is the default source, a present value routes per tenant.\n"
                + (multiTenant
                    ? "A present key outside the request tenant set is refused with a client error before\n"
                        + "any connection is taken. This is the one enforcer: every keyed acquisition in the\n"
                        + "carrier resolves through this method.\n"
                    : ""))
            .build();

        var dslFor = MethodSpec.methodBuilder("dslFor")
            .addModifiers(Modifier.PUBLIC)
            .returns(DSL_CONTEXT)
            .addParameter(tenantKey, "tenantKey")
            .addException(SQL_EXCEPTION)
            .addStatement("return entryFor($T.of(tenantKey)).dsl", ClassName.get("java.util", "Optional"))
            .addJavadoc("Returns the provider-bound {@code DSLContext} for {@code tenantKey}, pinning and\n"
                + "mounting one connection for the key on first use and reusing it thereafter. A drop-in for\n"
                + "{@code getDslContext(env)} at a routed fetcher site.\n"
                + "@param tenantKey the divined tenant value; an unknown key raises before any SQL\n")
            .build();

        var dslDefault = MethodSpec.methodBuilder("dslDefault")
            .addModifiers(Modifier.PUBLIC)
            .returns(DSL_CONTEXT)
            .addException(SQL_EXCEPTION)
            .addStatement("return entryFor($T.empty()).dsl", ClassName.get("java.util", "Optional"))
            .addJavadoc("Returns the provider-bound {@code DSLContext} for the default source, pinning and\n"
                + "mounting one connection on first use and reusing it thereafter. The untenanted\n"
                + "sibling of {@link #dslFor}, and always the fixed default source, whatever default\n"
                + "tenant the request names; the single-tenant path resolves every context through\n"
                + "this, as the one-key case.\n")
            .build();

        var dslGlobal = MethodSpec.methodBuilder("dslGlobal")
            .addModifiers(Modifier.PUBLIC)
            .returns(DSL_CONTEXT)
            .addException(SQL_EXCEPTION)
            .addStatement("return entryFor(defaultTenant).dsl")
            .addJavadoc("Returns the provider-bound {@code DSLContext} for graphitron's own reads of global\n"
                + "tables: the request's default tenant when it names one, else the default source. A\n"
                + "present default tenant resolves through {@link #entryFor} like any routed key, so it\n"
                + "shares the entry {@link #dslFor} uses for that tenant: one connection and one mount.\n"
                + "Writes and service calls keep {@link #dslDefault()}.\n")
            .build();

        var releaseAllBuilder = MethodSpec.methodBuilder("releaseAll")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class);
        if (multiTenant) {
            releaseAllBuilder
                .addComment("Close before draining, so a straggler worker that finishes pinning after this point")
                .addComment("aborts its own connection instead of leaking a fresh pin into a completed operation.")
                .addStatement("closed = true");
        }
        releaseAllBuilder.addStatement("$T failure = null", RuntimeException.class);
        if (multiTenant) {
            releaseAllBuilder
                .beginControlFlow("for ($T key : entries.keySet())", optionalKey)
                .addComment("remove() arbitrates each entry to exactly one processor, against a straggler's")
                .addComment("concurrent self-abort of the same entry.")
                .addStatement("$T entry = entries.remove(key)", entryType)
                .beginControlFlow("if (entry == null)")
                .addStatement("continue")
                .endControlFlow()
                .beginControlFlow("if (key.isPresent() && timedOutTenants.contains(key.get()))")
                .addComment("A TimedOut outcome means the join stopped waiting, not that the worker stopped")
                .addComment("working: the connection may still be mid-statement. A JDBC call cannot be safely")
                .addComment("killed, so route the straggler through the abort seam; never close or return a")
                .addComment("connection whose worker may still be executing on it.")
                .addStatement("entry.pinned.abort()")
                .addStatement("continue")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("entry.pinned.release()")
                .nextControlFlow("catch ($T e)", RuntimeException.class)
                .addComment("release() already evicted this connection on unmount failure; keep releasing the")
                .addComment("rest so one entry's failed unmount never orphans another's connection.")
                .beginControlFlow("if (failure == null)")
                .addStatement("failure = e")
                .endControlFlow()
                .endControlFlow()
                .endControlFlow();
        } else {
            releaseAllBuilder
                .beginControlFlow("for ($T entry : entries.values())", entryType)
                .beginControlFlow("try")
                .addStatement("entry.pinned.release()")
                .nextControlFlow("catch ($T e)", RuntimeException.class)
                .addComment("release() already evicted this connection on unmount failure; keep releasing the")
                .addComment("rest so one entry's failed unmount never orphans another's connection.")
                .beginControlFlow("if (failure == null)")
                .addStatement("failure = e")
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                .addStatement("entries.clear()");
        }
        var releaseAll = releaseAllBuilder
            .beginControlFlow("if (failure != null)")
            .addStatement("throw failure")
            .endControlFlow()
            .addJavadoc(multiTenant
                ? "Releases every pinned connection on every completion path (success, error,\n"
                    + "cancellation): each {@code release()} unmounts identity and returns or evicts its own\n"
                    + "connection, and one entry's unmount failure does not orphan the others. Idempotent:\n"
                    + "the map is drained, so a redundant call is a no-op. Rethrows the first release failure\n"
                    + "after attempting them all. A tenant whose scatter worker missed the join deadline is\n"
                    + "routed through {@code PinnedConnection.abort()}: its worker may still be executing, so\n"
                    + "the connection is evicted, never closed under a live statement nor returned to the pool.\n"
                : "Releases every pinned connection on every completion path (success, error,\n"
                    + "cancellation): each {@code release()} unmounts identity and returns or evicts its own\n"
                    + "connection, and one entry's unmount failure does not orphan the others. Idempotent:\n"
                    + "the map is cleared, so a redundant call is a no-op. Rethrows the first release failure\n"
                    + "after attempting them all.\n")
            .build();

        var entryClass = TypeSpec.classBuilder("Entry")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("One key's carrier entry: the pinned connection and its provider-bound\n"
                + "{@code DSLContext}, minted together inside the one {@code computeIfAbsent} and\n"
                + "discarded together at release. The mount's handle rides the {@code DSLContext}'s own\n"
                + "{@code Configuration.data()} slot, so it is created and destroyed with the connection\n"
                + "it describes and a {@code $$session} read is per-key by construction.\n")
            .addField(FieldSpec.builder(pinnedConnection, "pinned", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(DSL_CONTEXT, "dsl", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(pinnedConnection, "pinned")
                .addParameter(DSL_CONTEXT, "dsl")
                .addStatement("this.pinned = pinned")
                .addStatement("this.dsl = dsl")
                .build())
            .build();

        var carrier = TypeSpec.classBuilder(TENANT_CONNECTIONS_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc(multiTenant
                ? "Per-operation carrier of the keyed pinned connections for one request. See\n"
                    + "{@code ConnectionRuntimeClassGenerator} for the full contract. Concurrency is confined\n"
                    + "to {@link #scatter}'s bounded workers; everything else runs serially on the dispatch\n"
                    + "thread.\n"
                : "Per-operation carrier of the pinned connections for one request, with single-tenant as\n"
                    + "the one-key case of the unified per-key design. See\n"
                    + "{@code ConnectionRuntimeClassGenerator} for the full contract.\n");
        if (multiTenant) {
            carrier.addField(FieldSpec.builder(ParameterizedTypeName.get(DATA_TYPE, tenantKey), "TENANT_COLUMN_TYPE",
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$L.getDataType()", tenantColumn)
                    .addJavadoc("The representative tenant column's data type, its converter included: the coercion\n"
                        + "a client value takes on its way to the column, the same one the generated SQL binds it\n"
                        + "with.\n")
                    .build())
                .addField(scatterWorkerMarkerField);
        }
        carrier.addField(runtimeField)
            .addField(policyField);
        if (multiTenant) {
            carrier.addField(tenantsField)
                .addField(defaultTenantField)
                .addField(entityDefaultTenantField);
        }
        for (var p : payload) {
            carrier.addField(FieldSpec.builder(p.javaType(), p.name(), Modifier.PRIVATE, Modifier.FINAL)
                .addJavadoc("Mount payload, held for the life of the request; any fetcher may trigger the\n"
                    + "first mount for a key.\n")
                .build());
        }
        carrier.addField(entriesField);
        if (mounts) {
            carrier.addField(mountFailureField);
        }
        if (multiTenant) {
            carrier.addField(timedOutField)
                .addField(closedField);
        }
        carrier.addMethod(constructor)
            .addMethod(entryFor)
            .addMethod(dslFor)
            .addMethod(dslDefault)
            .addMethod(releaseAll)
            .addMethod(ofEnvironment(self))
            .addMethod(staticDslDefault(self))
            .addMethod(isMountFailure(mounts ? sessionMountException : null));
        if (mounts) {
            carrier.addMethod(MethodSpec.methodBuilder("mountFailure")
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get("java.util", "Optional"), sessionMountException))
                .addStatement("return $T.ofNullable(firstMountFailure.get())", ClassName.get("java.util", "Optional"))
                .addJavadoc("The operation's first rejected mount, if any. Read by the instrumentation when the\n"
                    + "operation completes, to throw it in place of the result.\n")
                .build());
        }
        if (handled) {
            carrier.addMethod(sessionHandleAccessor(handleType));
        }
        carrier.addType(entryClass);
        if (multiTenant) {
            carrier.addField(FieldSpec.builder(String.class, TENANTS_KEY_FIELD,
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", TENANTS_KEY_VALUE)
                .addJavadoc("The {@code graphQLContext} key the request tenant set is published under.\n"
                    + "Written by the generated factory's dedicated tenant-set parameter; read once by the\n"
                    + "execution instrumentation, which hands the decoded set to this carrier. One constant so\n"
                    + "the two sites cannot drift, and a graphitron-owned name no contextArgument can collide\n"
                    + "with.\n")
                .build());
            carrier.addField(FieldSpec.builder(SLF4J_LOGGER, "LOGGER",
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, self)
                .build());
            carrier.addMethod(scatterMethod(self, tenantKey))
                .addMethod(scatterWorkerMethod(self, tenantKey))
                .addMethod(fanOutDomain(self, tenantKey))
                .addMethod(narrowedFanOutDomain(tenantKey))
                .addMethod(namedTenants())
                .addMethod(handDown(tenantKey))
                .addMethod(fanOutRows(self, tenantKey))
                .addMethod(fanOutBatchRows(self, tenantKey))
                .addMethod(collapseFanOut(self))
                .addMethod(logFanOutFailure(self, errorRouter, mounts))
                .addMethod(staticDslFor(self, tenantKey))
                .addMethod(dslGlobal)
                .addMethod(staticDslGlobal(self))
                .addMethod(namedTenant(tenantKey))
                .addMethod(divinedTenant(tenantKey))
                .addMethod(tenantKeyOrNull(tenantKey, columnValues))
                .addMethod(canonicalTenantKey(tenantKey))
                .addMethod(tenantKeyOf(tenantKey, clientException))
                .addMethod(divinedTenantAgree(tenantKey, clientException))
                .addMethod(permits(self, tenantKey))
                .addMethod(staticEntityDefaultTenant(tenantKey))
                .addMethod(tenantSlot())
                .addMethod(loaderName())
                .addMethod(tenantLoaderName())
                .addMethod(tenantLoaderNameFor())
                .addType(outcomeType(self, tenantKey))
                .addType(fanOutFailureType());
        }
        return carrier.build();
    }

    /**
     * {@code static List<K> fanOutDomain(DataFetchingEnvironment env)}: the request's fan-out
     * domain, the intersection of the configured tenant map's keys and the carrier's request
     * tenant set (the tenants this request may touch in this deployment), with the two directions
     * of the difference treated differently: a hosted tenant outside the set is silently never
     * queried (the authorization pre-filter), while a tenant in the set that the deployment does
     * not host is a request-level error before any SQL runs (graphitron cannot tell a tenant
     * served by another deployment from a {@code DataSource} nobody wired, and skipping would
     * return incomplete results presented as complete). Iteration order is the tenant map's
     * configured key order filtered by the request set, so the union's concatenation order is
     * deployment-stable and the request collection's own iteration order is never load-bearing.
     */
    private static MethodSpec fanOutDomain(ClassName self, TypeName tenantKey) {
        return MethodSpec.methodBuilder("fanOutDomain")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(LIST, tenantKey))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("$T carrier = of(env)", self)
            .addStatement("$T<$T> requested = carrier.tenants", SET, tenantKey)
            .addStatement("$T<$T> hosted = carrier.runtime.tenantKeys()", SET, tenantKey)
            .beginControlFlow("for ($T claimed : requested)", tenantKey)
            .beginControlFlow("if (!hosted.contains(claimed))")
            .addStatement("throw new $T($S + claimed + $S)", NO_SUCH_ELEMENT,
                "The request's tenant set names tenant '",
                "', but this deployment hosts no DataSource for it; skipping it would return"
                    + " incomplete results presented as complete. Narrow the set at the factory"
                    + " when claims legitimately span more tenants than this subgraph hosts.")
            .endControlFlow()
            .endControlFlow()
            .addStatement("$T<$T> domain = new $T<>()", LIST, tenantKey, ARRAY_LIST)
            .beginControlFlow("for ($T hostedKey : hosted)", tenantKey)
            .beginControlFlow("if (requested.contains(hostedKey))")
            .addStatement("domain.add(hostedKey)")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return domain")
            .addJavadoc("The request's fan-out domain: the configured tenant map's keys, in configured\n"
                + "order, filtered by the request tenant set. A hosted tenant the request may not touch\n"
                + "is never queried; a tenant in the set that the deployment does not host is a\n"
                + "request-level error before any SQL runs.\n"
                + "@param env the fanned field's {@code DataFetchingEnvironment}\n")
            .build();
    }

    /**
     * {@code static List<K> fanOutDomain(DataFetchingEnvironment env, Object[] narrowing)}: the
     * fan-out domain narrowed by a fanned field's optional tenant slots, one runtime value per
     * slot. A slot contributes the set of tenants its value names only when every value its read
     * yields names one: a null anywhere (an absent argument, a null element, a filter element
     * leaving the slot out) or an empty read contributes nothing, since an element that leaves the
     * slot out may match rows in any tenant. The domain is the request's ({@link #fanOutDomain})
     * intersected with every contributed set; a named tenant outside the request set is silently
     * not queried, exactly as a hosted tenant outside it is not. Narrowing never changes which
     * rows answer, only how many tenants are asked, because a tenant's database holds only rows
     * naming that tenant.
     */
    private static MethodSpec narrowedFanOutDomain(TypeName tenantKey) {
        var hashSet = ClassName.get("java.util", "HashSet");
        return MethodSpec.methodBuilder("fanOutDomain")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(LIST, tenantKey))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(Object[].class, "narrowing")
            .addStatement("$T<$T> domain = fanOutDomain(env)", LIST, tenantKey)
            .beginControlFlow("for (Object slot : narrowing)")
            .addStatement("$T<Object> named = new $T<>()", LIST, ARRAY_LIST)
            .beginControlFlow("if (!namedTenants(slot, named) || named.isEmpty())")
            .addComment("Left out, or holding an element that leaves it out: this slot constrains nothing.")
            .addStatement("continue")
            .endControlFlow()
            .addStatement("$T<$T> keys = new $T<>()", SET, tenantKey, hashSet)
            .beginControlFlow("for (Object value : named)")
            .addStatement("keys.add(tenantKeyOf(value))")
            .endControlFlow()
            .addStatement("domain.retainAll(keys)")
            .endControlFlow()
            .addStatement("return domain")
            .addJavadoc("The request's fan-out domain narrowed to the tenants a fanned field's optional\n"
                + "tenant slots name. A slot whose value is absent, holds a null anywhere, or is empty\n"
                + "narrows nothing; the rest intersect, so only tenants every given slot names are\n"
                + "queried. A named tenant the request may not see is never queried.\n"
                + "@param env the fanned field's {@code DataFetchingEnvironment}\n"
                + "@param narrowing each optional slot's runtime value (a value, a collection, or {@code null})\n")
            .build();
    }

    /**
     * The flatten behind the narrowed domain: collects every leaf of {@code candidate} into
     * {@code out}, flattening collections as {@code agreeOnTenant} does, and answers false as soon
     * as a leaf is null.
     */
    private static MethodSpec namedTenants() {
        var collection = ClassName.get("java.util", "Collection");
        return MethodSpec.methodBuilder("namedTenants")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(Object.class, "candidate")
            .addParameter(ParameterizedTypeName.get(LIST, ClassName.get(Object.class)), "out")
            .beginControlFlow("if (candidate == null)")
            .addStatement("return false")
            .endControlFlow()
            .beginControlFlow("if (candidate instanceof $T<?> values)", collection)
            .beginControlFlow("for (Object value : values)")
            .beginControlFlow("if (!namedTenants(value, out))")
            .addStatement("return false")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return true")
            .endControlFlow()
            .addStatement("out.add(candidate)")
            .addStatement("return true")
            .build();
    }

    /**
     * {@code static boolean permits(DataFetchingEnvironment env, K tenantKey)}: whether the
     * request tenant set admits {@code tenantKey}. The read side of the rule {@code entryFor}
     * enforces, for surfaces that answer a refused tenant as presentation rather than an error
     * (the per-row lookups skip a refused group so its positions stay {@code null}); the rule
     * itself stays single-sourced on the carrier.
     */
    private static MethodSpec permits(ClassName self, TypeName tenantKey) {
        return MethodSpec.methodBuilder("permits")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(tenantKey, "tenantKey")
            .addStatement("return of(env).tenants.contains(tenantKey)")
            .addJavadoc("Whether the request tenant set admits {@code tenantKey}. {@link #entryFor} stays the\n"
                + "enforcer; this lets a per-row lookup skip a refused tenant's group, answering its ids\n"
                + "with {@code null} exactly as it answers an id that does not exist.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n"
                + "@param tenantKey the divined tenant value\n")
            .build();
    }

    /**
     * {@code static Optional<K> entityDefaultTenant(DataFetchingEnvironment env)}: the request's
     * entity default tenant, as the carrier holds it. Read by the entity dispatcher for a
     * {@code @defaultTenant} type, whose representations group on it; empty when the request names
     * none, and then every such representation answers {@code null}.
     */
    private static MethodSpec staticEntityDefaultTenant(TypeName tenantKey) {
        return MethodSpec.methodBuilder("entityDefaultTenant")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(OPTIONAL, tenantKey))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("return of(env).entityDefaultTenant")
            .addJavadoc("The request's entity default tenant, or empty when the request names none. The\n"
                + "entity dispatcher resolves a {@code @defaultTenant} type's representations on it.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n")
            .build();
    }

    /**
     * {@code static <T> DataFetcherResult<T> handDown(T row, K tenantKey)}: the per-element tenant
     * stamp. Wraps one row as the field value graphql-java completes for it, carrying the row's
     * tenant as {@code localContext} so the subtree below that one element reads it back. Both
     * per-row surfaces call it: the fan-out union ({@link #fanOutRows}, {@link #fanOutBatchRows})
     * and the node and entity dispatcher's per-tenant groups. Typed on the build's tenant key so
     * it is no general-purpose way into the slot.
     */
    private static MethodSpec handDown(TypeName tenantKey) {
        var t = no.sikt.graphitron.javapoet.TypeVariableName.get("T");
        return MethodSpec.methodBuilder(HAND_DOWN_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t)
            .returns(ParameterizedTypeName.get(DATA_FETCHER_RESULT, t))
            .addParameter(t, "row")
            .addParameter(tenantKey, "tenantKey")
            .addStatement("return $T.<$T>newResult().data(row).localContext(tenantKey).build()",
                DATA_FETCHER_RESULT, t)
            .addJavadoc("Wraps one row as its own field value, handing {@code tenantKey} down to the\n"
                + "row's subtree: the per-element tenant stamp of the fan-out union and of node and\n"
                + "entity dispatch. graphql-java unwraps a list's elements one by one, so each element\n"
                + "keeps its own tenant.\n"
                + "@param row the resolved row\n"
                + "@param tenantKey the tenant the row was read from\n")
            .build();
    }

    /**
     * {@code static <R> List<Object> fanOutRows(env, perTenant)}: the root-field fan-out union.
     * Scatters the per-tenant statement over the domain, flattens each {@code Success} outcome's
     * rows into per-element {@code DataFetcherResult}s carrying that row's tenant as
     * {@code localContext} (graphql-java unwraps list elements individually, so children below the
     * fanned field read the right tenant with no further machinery), and appends one
     * {@code FanOutFailure} marker per failed or timed-out tenant after the successful rows.
     * {@code collapseFanOut} turns the markers into null elements plus path-bearing errors.
     */
    private static MethodSpec fanOutRows(ClassName self, TypeName tenantKey) {
        var r = no.sikt.graphitron.javapoet.TypeVariableName.get("R");
        var outcome = self.nestedClass("Outcome");
        var listOfR = ParameterizedTypeName.get(LIST, r);
        return MethodSpec.methodBuilder("fanOutRows")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(r)
            .returns(ParameterizedTypeName.get(LIST, ClassName.get(Object.class)))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(ParameterizedTypeName.get(FUNCTION, DSL_CONTEXT, listOfR), "perTenant")
            .addParameter(Object[].class, "narrowing")
            .varargs()
            .addStatement("$T<$T<$T>> outcomes = of(env).scatter(fanOutDomain(env, narrowing), perTenant)",
                LIST, outcome, listOfR)
            .addStatement("$T<Object> elements = new $T<>()", LIST, ARRAY_LIST)
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfR)
            .beginControlFlow("if (outcome instanceof Outcome.Success<$T> success)", listOfR)
            .beginControlFlow("for ($T row : success.value())", r)
            .addStatement("elements.add($L(row, success.key()))", HAND_DOWN_METHOD)
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addComment("Failed and timed-out tenants append after the successful rows (we cannot know how")
            .addComment("many rows they would have returned), one marker each.")
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfR)
            .beginControlFlow("if (!(outcome instanceof Outcome.Success))")
            .addStatement("elements.add(logFanOutFailure(outcome, env))")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return elements")
            .addJavadoc("Runs the fanned field's statement once per domain tenant and unions the outcomes\n"
                + "in domain order: each row wrapped as a per-element {@code DataFetcherResult} whose\n"
                + "{@code localContext} carries the row's tenant, each failed or timed-out tenant as one\n"
                + "{@code FanOutFailure} marker appended after the successful rows. Feed the result to\n"
                + "{@link #collapseFanOut}. {@code narrowing} restricts the domain to the tenants the\n"
                + "field's optional tenant slots name; none given is the request's whole domain.\n")
            .build();
    }

    /**
     * {@code static <R> List<List<Object>> fanOutBatchRows(env, keyCount, perTenant)}: the batched
     * sibling of {@link #fanOutRows} for a fanned field under an untenanted parent. One scatter
     * per parent batch; {@code perTenant} runs the batch statement (per-key grouped) once per
     * tenant, and the per-key groups merge across tenants in domain order, each row stamped with
     * its tenant. A failed or timed-out tenant contributes one shared marker to <em>every</em>
     * parent's list (the failure hides that tenant's rows for every parent in the batch); each
     * parent's fetcher collapses its own list against its own path.
     */
    private static MethodSpec fanOutBatchRows(ClassName self, TypeName tenantKey) {
        var r = no.sikt.graphitron.javapoet.TypeVariableName.get("R");
        var outcome = self.nestedClass("Outcome");
        var listOfListOfR = ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, r));
        var listOfObject = ParameterizedTypeName.get(LIST, ClassName.get(Object.class));
        return MethodSpec.methodBuilder("fanOutBatchRows")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(r)
            .returns(ParameterizedTypeName.get(LIST, listOfObject))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(int.class, "keyCount")
            .addParameter(ParameterizedTypeName.get(FUNCTION, DSL_CONTEXT, listOfListOfR), "perTenant")
            .addParameter(Object[].class, "narrowing")
            .varargs()
            .addStatement("$T<$T<$T>> outcomes = of(env).scatter(fanOutDomain(env, narrowing), perTenant)",
                LIST, outcome, listOfListOfR)
            .addStatement("$T<$T> merged = new $T<>(keyCount)", LIST, listOfObject, ARRAY_LIST)
            .beginControlFlow("for (int i = 0; i < keyCount; i++)")
            .addStatement("merged.add(new $T<>())", ARRAY_LIST)
            .endControlFlow()
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfListOfR)
            .beginControlFlow("if (outcome instanceof Outcome.Success<$T> success)", listOfListOfR)
            .beginControlFlow("for (int i = 0; i < keyCount; i++)")
            .beginControlFlow("for ($T row : success.value().get(i))", r)
            .addStatement("merged.get(i).add($L(row, success.key()))", HAND_DOWN_METHOD)
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfListOfR)
            .beginControlFlow("if (!(outcome instanceof Outcome.Success))")
            .addStatement("$T failure = logFanOutFailure(outcome, env)", self.nestedClass("FanOutFailure"))
            .beginControlFlow("for (int i = 0; i < keyCount; i++)")
            .addStatement("merged.get(i).add(failure)")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement("return merged")
            .addJavadoc("The batched form of {@link #fanOutRows}: one scatter per parent batch, one\n"
                + "statement per tenant per batch, per-key groups merged across tenants in domain order\n"
                + "with per-element tenant stamping. A failed tenant contributes one shared marker to\n"
                + "every parent's list; each parent's fetcher collapses against its own path.\n"
                + "@param keyCount the parent batch size; {@code perTenant} must return one group per key\n"
                + "@param narrowing the field's optional tenant slot values, read off the batch's environment\n")
            .build();
    }

    /**
     * {@code static DataFetcherResult<List<Object>> collapseFanOut(env, items)}: turns a
     * marker-bearing element list into the fanned field's result. Every {@code FanOutFailure}
     * marker becomes one {@code null} element plus one error whose {@code path} points at that
     * element's index; SDL element nullability then composes the author's strictness for free
     * ({@code [Thing]} keeps partial data, {@code [Thing!]} lets graphql-java's null-bubbling
     * turn any tenant failure into a null field). The message carries only a correlation-id
     * reference (details are in the server log); a machine-readable classification rides in
     * {@code extensions}.
     */
    private static MethodSpec collapseFanOut(ClassName self) {
        var listOfObject = ParameterizedTypeName.get(LIST, ClassName.get(Object.class));
        return MethodSpec.methodBuilder("collapseFanOut")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(DATA_FETCHER_RESULT, listOfObject))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(listOfObject, "items")
            .addStatement("$T<Object> elements = new $T<>(items.size())", LIST, ARRAY_LIST)
            .addStatement("$T<$T> errors = new $T<>()", LIST, GRAPHQL_ERROR, ARRAY_LIST)
            .beginControlFlow("for (Object item : items)")
            .beginControlFlow("if (item instanceof FanOutFailure failure)")
            .addCode("errors.add($T.newError(env)\n", GRAPHQL_ERROR_BUILDER)
            .addCode("    .message($S + failure.correlationId() + $S)\n",
                "A tenant's data did not arrive. Reference: ", ".")
            .addCode("    .path(env.getExecutionStepInfo().getPath().segment(elements.size()))\n")
            .addCode("    .extensions($T.<String, Object>of($S, failure.classification()))\n", MAP, "classification")
            .addCode("    .build());\n")
            .addStatement("elements.add(null)")
            .nextControlFlow("else")
            .addStatement("elements.add(item)")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return $T.<$T>newResult().data(elements).errors(errors).build()",
                DATA_FETCHER_RESULT, listOfObject)
            .addJavadoc("Collapses a {@link #fanOutRows} / {@link #fanOutBatchRows} element list into the\n"
                + "fanned field's {@code DataFetcherResult}: markers become null elements plus\n"
                + "path-bearing redacted errors (correlation-id reference in the message, classification\n"
                + "in {@code extensions}); everything else passes through as the per-element\n"
                + "tenant-stamped results.\n")
            .build();
    }

    /**
     * Logs one failure under a reference from {@code ErrorRouter.newReference} and returns its
     * in-band marker. A configured {@code FetcherErrorLog} takes the failure through its
     * {@code tenantFailed} (a timeout as a synthesized {@code TimeoutException}, so the consumer's
     * damping sees outages too); without one, the default line for the arm is written.
     */
    private static MethodSpec logFanOutFailure(ClassName self, ClassName errorRouter, boolean mounts) {
        var outcome = self.nestedClass("Outcome");
        var failureType = self.nestedClass("FanOutFailure");
        var builder = MethodSpec.methodBuilder("logFanOutFailure")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(failureType)
            .addParameter(ParameterizedTypeName.get(outcome, WildcardTypeName.subtypeOf(Object.class)), "outcome")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .beginControlFlow("if (outcome instanceof Outcome.Failed<?> failed)");
        if (mounts) {
            builder
                .beginControlFlow("if (isMountFailure(failed.cause()))")
                .addComment("A rejected mount fails the whole request when the operation completes; this marker")
                .addComment("never reaches a client, so nothing is logged and no reference is minted.")
                .addStatement("return new FanOutFailure($S, FanOutFailure.FAILED)", "")
                .endControlFlow();
        }
        return builder
            .addStatement("String reference = $T.newReference(failed.cause(), env)", errorRouter)
            .beginControlFlow("if (!$T.toConfiguredLog(env, reference,"
                + " log -> log.tenantFailed(failed.key(), failed.cause(), reference, env)))", errorRouter)
            .addStatement("LOGGER.error($S, failed.key(), reference, failed.cause())",
                "Tenant fan-out failed for tenant {}; correlation id = {}")
            .endControlFlow()
            .addStatement("return new FanOutFailure(reference, FanOutFailure.FAILED)")
            .endControlFlow()
            .beginControlFlow("if (outcome instanceof Outcome.TimedOut)")
            .addStatement("$T timeout = new $T($S)", TIMEOUT_EXCEPTION, TIMEOUT_EXCEPTION,
                "tenant fan-out passed its deadline")
            .addStatement("String reference = $T.newReference(timeout, env)", errorRouter)
            .beginControlFlow("if (!$T.toConfiguredLog(env, reference,"
                + " log -> log.tenantFailed(outcome.key(), timeout, reference, env)))", errorRouter)
            .addStatement("LOGGER.error($S, outcome.key(), reference)",
                "Tenant fan-out timed out for tenant {}; correlation id = {}")
            .endControlFlow()
            .addStatement("return new FanOutFailure(reference, FanOutFailure.TIMED_OUT)")
            .endControlFlow()
            .addComment("Callers guard out Success, and Java 17 offers no sealed-switch exhaustiveness here:")
            .addComment("fail loud so a future Outcome arm cannot silently misclassify as a timeout.")
            .addStatement("throw new $T($S + outcome)", IllegalStateException.class,
                "Unclassifiable fan-out outcome: ")
            .addJavadoc("Redaction seam for per-tenant failures: the cause and tenant go to the server log\n"
                + "under a fresh correlation id; only the id and a machine-readable classification\n"
                + "travel to the client, on the marker.\n")
            .build();
    }

    /**
     * {@code static boolean isMountFailure(Throwable thrown)}: whether a rejected mount is anywhere
     * on {@code thrown}'s cause chain. Emitted in every build so the fixed {@code ErrorRouter} can
     * ask without naming {@code SessionMountException}, which exists only with a mount; without
     * one ({@code sessionMountException == null}) the answer is always {@code false}.
     */
    private static MethodSpec isMountFailure(ClassName sessionMountException) {
        var builder = MethodSpec.methodBuilder("isMountFailure")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(Throwable.class, "thrown");
        if (sessionMountException == null) {
            builder.addStatement("return false");
        } else {
            builder
                .beginControlFlow("for ($T t = thrown; t != null; t = t.getCause())", Throwable.class)
                .beginControlFlow("if (t instanceof $T)", sessionMountException)
                .addStatement("return true")
                .endControlFlow()
                .endControlFlow()
                .addStatement("return false");
        }
        return builder
            .addJavadoc("Whether {@code thrown}'s cause chain carries a rejected session mount. The error\n"
                + "router declines such a throw: the operation fails as a whole when it completes, so no\n"
                + "field logs it, mints a reference for it, or places it in a payload.\n"
                + "@param thrown what a fetcher caught\n")
            .build();
    }

    /**
     * The consumer-implemented {@code FetcherErrorLog}: one abstract method, so it stays a
     * functional interface and every later hook point ({@code matched}, {@code tenantFailed}) is a
     * default method that breaks no implementor. {@code tenantFailed} exists only when
     * {@code tenantKey} is non-null (a {@code <tenantColumn>} build), typed with that key, so a
     * single-tenant build exposes no method that can never fire.
     */
    private static TypeSpec fetcherErrorLog(ClassName self, TypeName tenantKey) {
        var unmatched = MethodSpec.methodBuilder("unmatched")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addParameter(Throwable.class, "thrown")
            .addParameter(String.class, "reference")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addJavadoc("Called once per fetcher exception no {@code @error} mapping claims, in place of\n"
                + "Graphitron's own log line. Graphitron logs nothing else about the exception, so the\n"
                + "reference is the only thing that joins a client's report to this log line.\n"
                + "@param thrown the exception the fetcher threw, message and cause chain intact\n"
                + "@param reference the text the client received after {@code \"Reference:\"}\n"
                + "@param env the failing field's environment (its path, field definition and arguments)\n")
            .build();
        var matched = MethodSpec.methodBuilder("matched")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .addParameter(Throwable.class, "thrown")
            .addParameter(Throwable.class, "claimed")
            .addParameter(String.class, "reference")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addJavadoc("Called once for each exception an {@code @error} handler catches in a request, however\n"
                + "many fields it failed. Graphitron logs nothing about a caught exception, which is right\n"
                + "for a business error the client is meant to handle; override this to log what a broad\n"
                + "handler last in a union (a catch-all) catches, so an outage does not reach only the\n"
                + "client. Does nothing by default. {@code VALIDATION} errors and the column-length check\n"
                + "are not exceptions and never reach it.\n"
                + "@param thrown the exception the fetcher threw, message and cause chain intact\n"
                + "@param claimed the exception the handler caught: {@code thrown} or a link of its cause\n"
                + "chain, and the source of the payload's error entry\n"
                + "@param reference the reference the client can be given for this error, the one\n"
                + "{@code FetcherErrorResponse.matchedMessage} receives\n"
                + "@param env the environment of the first field that failed with it\n")
            .build();
        var builder = TypeSpec.interfaceBuilder(self)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(FunctionalInterface.class)
            .addJavadoc("Decides how a fetcher exception is logged. By default Graphitron logs an exception no\n"
                + "{@code @error} mapping claims at ERROR through the {@code ErrorRouter} logger, message and\n"
                + "cause chain included (an exception message can carry row data), and logs nothing about\n"
                + "one a handler caught. Give the runtime one of these\n"
                + "({@code GraphitronRuntime.withFetcherErrorLog}) to decide yourself what reaches your logs:\n"
                + "levels, sanitising and rate limiting are yours to build here. {@link #unmatched} is the\n"
                + "one abstract method, so a lambda is a complete log; {@link #matched} is called once per\n"
                + "caught exception per request. The client's response does not change either way.\n"
                + "\n"
                + "<p>The reference each method receives is minted by\n"
                + "{@code FetcherErrorResponse.reference}, so a runtime with a log and no response gets the\n"
                + "default, a random UUID.\n"
                + "\n"
                + "<p>On the escape-hatch engine, which has no runtime, put the log into each request's\n"
                + "{@code GraphQLContext} under this class. A request without that entry gets the default\n"
                + "line. If the log throws, the client still gets its response and Graphitron logs one\n"
                + "ERROR line naming the log's class, the class of what it threw and the reference; only\n"
                + "JVM-fatal errors propagate.\n")
            .addMethod(unmatched)
            .addMethod(matched);
        if (tenantKey != null) {
            builder.addMethod(MethodSpec.methodBuilder("tenantFailed")
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                .addParameter(tenantKey, "tenantKey")
                .addParameter(Throwable.class, "cause")
                .addParameter(String.class, "reference")
                .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
                .addStatement("unmatched(cause, reference, env)")
                .addJavadoc("Called once per tenant that failed or timed out in a field fanned out across\n"
                    + "tenants, in place of Graphitron's own log line. Defaults to {@link #unmatched};\n"
                    + "override it to log the tenant too.\n"
                    + "@param tenantKey the tenant whose part of the field did not arrive\n"
                    + "@param cause what the tenant's worker threw, or a\n"
                    + "{@link java.util.concurrent.TimeoutException} when it passed the fan-out deadline\n"
                    + "@param reference the text the client received after {@code \"Reference:\"}\n"
                    + "@param env the fanned field's environment\n")
                .build());
        }
        return builder.build();
    }

    /**
     * The consumer-implemented {@code FetcherErrorResponse}: three default methods and no abstract
     * one, so it is deliberately not a functional interface and a consumer overrides only what it
     * needs. The defaults are the only spelling of Graphitron's client-facing text for a fetcher
     * exception: {@code ErrorRouter} calls a default instance when none is configured rather than
     * restating them.
     */
    private static TypeSpec fetcherErrorResponse(ClassName self) {
        var reference = MethodSpec.methodBuilder("reference")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(String.class)
            .addParameter(Throwable.class, "thrown")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("return $T.randomUUID().toString()", UUID_CLASS)
            .addJavadoc("Mints the text that joins a client's report to your log line, once per exception and\n"
                + "request. The same reference reaches the {@code FetcherErrorLog} and the two message\n"
                + "methods. The default is a random UUID; a trace id lets an operator go straight from a\n"
                + "support ticket to the trace. A fetcher may run on a thread that does not carry the\n"
                + "request's tracing context, so read the trace id from the request's {@code GraphQLContext}.\n"
                + "@param thrown the exception the reference is for: a caught error's source, or the\n"
                + "exception no handler caught\n"
                + "@param env the failing field's environment\n"
                + "@return the reference; {@code null} falls back to the default\n")
            .build();
        var matchedMessage = MethodSpec.methodBuilder("matchedMessage")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(String.class)
            .addParameter(Throwable.class, "source")
            .addParameter(String.class, "defaultMessage")
            .addParameter(String.class, "reference")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("return defaultMessage")
            .addJavadoc("The {@code message:} of an error a handler caught, called when the field is read. Return\n"
                + "{@code defaultMessage} for the errors you leave alone.\n"
                + "@param source the exception the handler caught, the error entry's source\n"
                + "@param defaultMessage what Graphitron returns without this method: the handler's\n"
                + "{@code description:}, else the exception's own message\n"
                + "@param reference the reference minted for {@code source}, the one the log received\n"
                + "@param env the {@code message} field's environment; {@code env.getParentType()} is the\n"
                + "{@code @error} type\n"
                + "@return the client's message; {@code null} falls back to {@code defaultMessage}\n")
            .build();
        var unmatchedMessage = MethodSpec.methodBuilder("unmatchedMessage")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(String.class)
            .addParameter(Throwable.class, "thrown")
            .addParameter(String.class, "reference")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("return $S + reference + $S", "An error occurred. Reference: ", ".")
            .addJavadoc("The top-level error's message when no handler caught the exception. It is the\n"
                + "client-facing text of a fault nobody planned for, so do not put the exception's message\n"
                + "in it unless you know what it contains.\n"
                + "@param thrown the exception the fetcher threw\n"
                + "@param reference the reference minted for {@code thrown}, the one the log received\n"
                + "@param env the failing field's environment\n"
                + "@return the client's message; {@code null} falls back to the default\n")
            .build();
        return TypeSpec.interfaceBuilder(self)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Decides what the client reads for a fetcher exception: its reference, the\n"
                + "{@code message:} of an error a handler caught, and the top-level message of one none\n"
                + "caught. Every method has a default reproducing what Graphitron does without one, so\n"
                + "override only what you need. Give the runtime one of these\n"
                + "({@code GraphitronRuntime.withFetcherErrorResponse}); on the escape-hatch engine, which\n"
                + "has no runtime, put it into each request's {@code GraphQLContext} under this class.\n"
                + "\n"
                + "<p>If a method throws or returns {@code null}, the client gets the default text and\n"
                + "Graphitron logs one ERROR line naming the response's class, the class of what it threw\n"
                + "and the reference; only JVM-fatal errors propagate.\n")
            .addMethod(reference)
            .addMethod(matchedMessage)
            .addMethod(unmatchedMessage)
            .build();
    }

    /** The in-band per-tenant failure marker {@code collapseFanOut} turns into a null element + error. */
    private static TypeSpec fanOutFailureType() {
        return TypeSpec.classBuilder("FanOutFailure")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("One failed or timed-out tenant in a fan-out union, travelling in-band through the\n"
                + "element lists until {@link #collapseFanOut} turns it into a null element plus a\n"
                + "path-bearing error. Carries only the redacted facts (correlation id, classification);\n"
                + "the cause and the tenant key stay in the server log.\n")
            .addField(FieldSpec.builder(String.class, "FAILED",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", FAN_OUT_FAILED_CLASSIFICATION)
                .addJavadoc("The {@code extensions.classification} value for a tenant whose worker threw.\n")
                .build())
            .addField(FieldSpec.builder(String.class, "TIMED_OUT",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", FAN_OUT_TIMED_OUT_CLASSIFICATION)
                .addJavadoc("The {@code extensions.classification} value for a tenant past the scatter deadline.\n")
                .build())
            .addField(FieldSpec.builder(String.class, "correlationId", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(String.class, "classification", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(String.class, "correlationId")
                .addParameter(String.class, "classification")
                .addStatement("this.correlationId = correlationId")
                .addStatement("this.classification = classification")
                .build())
            .addMethod(MethodSpec.methodBuilder("correlationId")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return correlationId")
                .addJavadoc("The reference logged with the failure's cause in the server log.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("classification")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return classification")
                .addJavadoc("Machine-readable failure kind for the error's {@code extensions}.\n")
                .build())
            .build();
    }

    /**
     * {@code <R> List<Outcome<R>> scatter(Collection<K> keys, Function<DSLContext, R> perTenant)}:
     * the one place concurrency lives in the runtime. Every distinct key gets one worker on the
     * runtime's bounded fan-out executor; each worker resolves its {@code DSLContext} through
     * {@code dslFor(key)} so the pin-and-mount recipe stays single-sourced and per-tenant RLS
     * composes unchanged. The calling dispatch thread blocks in the join until every worker
     * completes or the deadline passes, then returns outcomes in key iteration order. Policy-neutral
     * about partial failure: every tenant ends as exactly one {@code Outcome}, nothing is dropped or
     * cancelled at this layer, and the caller decides what a {@code Failed} or {@code TimedOut}
     * tenant means.
     */
    private static MethodSpec scatterMethod(ClassName self, TypeName tenantKey) {
        var r = no.sikt.graphitron.javapoet.TypeVariableName.get("R");
        var outcome = self.nestedClass("Outcome");
        var outcomeOfR = ParameterizedTypeName.get(outcome, r);
        return MethodSpec.methodBuilder("scatter")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(r)
            .returns(ParameterizedTypeName.get(LIST, outcomeOfR))
            .addParameter(ParameterizedTypeName.get(COLLECTION, tenantKey), "keys")
            .addParameter(ParameterizedTypeName.get(FUNCTION, DSL_CONTEXT, r), "perTenant")
            .beginControlFlow("if ($T.TRUE.equals(SCATTER_WORKER.get()))", Boolean.class)
            .addStatement("throw new $T($S)", IllegalStateException.class,
                "scatter is not re-entrant: a perTenant body must never call scatter (a bounded pool"
                    + " waiting on itself deadlocks).")
            .endControlFlow()
            .addComment("Distinct keys, one worker each, in the caller's iteration order: a duplicate key")
            .addComment("would put a second concurrent worker on one pinned connection.")
            .addStatement("$T<$T> order = new $T<>(new $T<>(keys))", LIST, tenantKey, ARRAY_LIST, LINKED_HASH_SET)
            .addStatement("$T<$T<$T>> futures = new $T<>(order.size())",
                LIST, COMPLETABLE_FUTURE, outcomeOfR, ARRAY_LIST)
            .addStatement("int submitted = 0")
            .beginControlFlow("try")
            .beginControlFlow("while (submitted < order.size())")
            .addStatement("$T key = order.get(submitted)", tenantKey)
            .addStatement("futures.add($T.supplyAsync(() -> scatterWorker(key, perTenant), runtime.fanOutExecutor()))",
                COMPLETABLE_FUTURE)
            .addStatement("submitted++")
            .endControlFlow()
            .nextControlFlow("catch ($T e)", ClassName.get("java.util.concurrent", "RejectedExecutionException"))
            .addComment("A consumer-supplied executor rejected mid-submit (the runtime's own bounded pool")
            .addComment("never rejects); already-submitted workers may be running. Quarantine their keys so")
            .addComment("dslFor never reuses them and releaseAll aborts rather than closes, then propagate:")
            .addComment("the fanned field fails as one unit.")
            .beginControlFlow("for (int i = 0; i < submitted; i++)")
            .addStatement("timedOutTenants.add(order.get(i))")
            .endControlFlow()
            .addStatement("throw e")
            .endControlFlow()
            .addStatement("long deadline = $T.nanoTime() + runtime.fanOutTimeout().toNanos()", System.class)
            .addStatement("$T<$T> outcomes = new $T<>(order.size())", LIST, outcomeOfR, ARRAY_LIST)
            .beginControlFlow("for (int i = 0; i < order.size(); i++)")
            .addStatement("$T key = order.get(i)", tenantKey)
            .beginControlFlow("try")
            .addStatement("outcomes.add(futures.get(i).get($T.max(0L, deadline - $T.nanoTime()), $T.NANOSECONDS))",
                Math.class, System.class, TIME_UNIT)
            .nextControlFlow("catch ($T e)", TIMEOUT_EXCEPTION)
            .addComment("The join stops waiting; the worker is not interrupted (a JDBC call cannot be safely")
            .addComment("killed). The key is quarantined: dslFor never reuses it and releaseAll aborts it.")
            .addStatement("timedOutTenants.add(key)")
            .addStatement("outcomes.add(new Outcome.TimedOut<>(key))")
            .nextControlFlow("catch ($T e)", EXECUTION_EXCEPTION)
            .addComment("Defensive: the worker catches Throwable itself; only an executor-level failure lands here.")
            .addStatement("outcomes.add(new Outcome.Failed<>(key, e.getCause()))")
            .nextControlFlow("catch ($T e)", InterruptedException.class)
            .addComment("The join stopped waiting (and the re-set interrupt makes every following get throw")
            .addComment("immediately), but no worker is stopped: quarantine the key exactly like a timeout, so")
            .addComment("its possibly-still-executing connection is never reused and releaseAll aborts rather")
            .addComment("than closes. Failed-vs-TimedOut on the outcome is caller policy; the quarantine is")
            .addComment("the load-bearing part.")
            .addStatement("$T.currentThread().interrupt()", Thread.class)
            .addStatement("timedOutTenants.add(key)")
            .addStatement("outcomes.add(new Outcome.Failed<>(key, e))")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return outcomes")
            .addJavadoc("Runs {@code perTenant} once per distinct key on the runtime's bounded fan-out\n"
                + "executor, each worker on its own tenant's pinned connection (resolved through\n"
                + "{@link #dslFor}, so per-tenant session identity and RLS compose unchanged), and blocks\n"
                + "until every worker completes or the deadline passes. Outcomes come back in the iteration\n"
                + "order of {@code keys} (duplicates collapse to the first occurrence), one per key:\n"
                + "{@code Success} (an empty result is a Success carrying an empty value, distinct from\n"
                + "failure), {@code Failed} (the worker threw; the cause is carried, never swallowed), or\n"
                + "{@code TimedOut} (the deadline passed first; the worker is not interrupted, its\n"
                + "connection is quarantined and aborted at release). Policy-neutral about partial failure:\n"
                + "nothing is dropped or cancelled here, and the caller decides what non-success means.\n"
                + "Workers never touch the default connection, and the dispatch thread is blocked inside\n"
                + "the join for the scatter's whole duration. Not re-entrant: a {@code perTenant} body\n"
                + "calling scatter throws immediately rather than deadlocking the bounded pool.\n"
                + "@param keys the fan-out domain, iterated in order; duplicates collapse\n"
                + "@param perTenant the per-tenant unit of work, handed only the keyed {@code DSLContext}\n")
            .build();
    }

    /** The scatter worker body: marker for the re-entrancy guard, Success/Failed fold, never throws. */
    private static MethodSpec scatterWorkerMethod(ClassName self, TypeName tenantKey) {
        var r = no.sikt.graphitron.javapoet.TypeVariableName.get("R");
        var outcome = self.nestedClass("Outcome");
        return MethodSpec.methodBuilder("scatterWorker")
            .addModifiers(Modifier.PRIVATE)
            .addTypeVariable(r)
            .returns(ParameterizedTypeName.get(outcome, r))
            .addParameter(tenantKey, "key")
            .addParameter(ParameterizedTypeName.get(FUNCTION, DSL_CONTEXT, r), "perTenant")
            .addStatement("SCATTER_WORKER.set($T.TRUE)", Boolean.class)
            .beginControlFlow("try")
            .addStatement("return new Outcome.Success<>(key, perTenant.apply(dslFor(key)))")
            .nextControlFlow("catch ($T cause)", Throwable.class)
            .addStatement("return new Outcome.Failed<>(key, cause)")
            .nextControlFlow("finally")
            .addStatement("SCATTER_WORKER.remove()")
            .endControlFlow()
            .addJavadoc("One tenant's unit of work on a fan-out executor thread: pin-or-reuse the key's\n"
                + "connection through {@link #dslFor}, apply {@code perTenant}, fold the result or any\n"
                + "throwable into exactly one {@code Outcome}. Never throws; the cause travels on the\n"
                + "{@code Failed} arm.\n")
            .build();
    }

    /**
     * The per-tenant scatter outcome taxonomy: a sealed interface with one arm per way a tenant's
     * unit of work can end. Emitted as final classes with record-style accessors (the generated
     * output targets Java 17 and the emitter writes explicit classes); all three arms live in this
     * compilation unit, so the sealed interface needs no {@code permits} clause.
     */
    private static TypeSpec outcomeType(ClassName self, TypeName tenantKey) {
        var r = no.sikt.graphitron.javapoet.TypeVariableName.get("R");
        var outcome = self.nestedClass("Outcome");
        var outcomeOfR = ParameterizedTypeName.get(outcome, r);

        var keyAccessor = MethodSpec.methodBuilder("key")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(tenantKey)
            .addJavadoc("The tenant key this outcome belongs to.\n")
            .build();

        var success = TypeSpec.classBuilder("Success")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addTypeVariable(r)
            .addSuperinterface(outcomeOfR)
            .addJavadoc("The worker completed; {@code value} is {@code perTenant}'s result. An empty result\n"
                + "is a Success carrying an empty value, distinct from {@code Failed}: conflating the two\n"
                + "is exactly the incomplete-presented-as-complete confusion the error posture prevents.\n")
            .addField(FieldSpec.builder(tenantKey, "key", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(r, "value", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(tenantKey, "key")
                .addParameter(r, "value")
                .addStatement("this.key = key")
                .addStatement("this.value = value")
                .build())
            .addMethod(MethodSpec.methodBuilder("key")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(tenantKey)
                .addStatement("return key")
                .build())
            .addMethod(MethodSpec.methodBuilder("value")
                .addModifiers(Modifier.PUBLIC)
                .returns(r)
                .addStatement("return value")
                .addJavadoc("The worker's result; may be an empty collection, never a signal of failure.\n")
                .build())
            .build();

        var failed = TypeSpec.classBuilder("Failed")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addTypeVariable(r)
            .addSuperinterface(outcomeOfR)
            .addJavadoc("The worker threw; {@code cause} carries the throwable, never swallowed.\n")
            .addField(FieldSpec.builder(tenantKey, "key", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(Throwable.class, "cause", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(tenantKey, "key")
                .addParameter(Throwable.class, "cause")
                .addStatement("this.key = key")
                .addStatement("this.cause = cause")
                .build())
            .addMethod(MethodSpec.methodBuilder("key")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(tenantKey)
                .addStatement("return key")
                .build())
            .addMethod(MethodSpec.methodBuilder("cause")
                .addModifiers(Modifier.PUBLIC)
                .returns(Throwable.class)
                .addStatement("return cause")
                .addJavadoc("What the worker threw.\n")
                .build())
            .build();

        var timedOut = TypeSpec.classBuilder("TimedOut")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addTypeVariable(r)
            .addSuperinterface(outcomeOfR)
            .addJavadoc("The scatter deadline passed before the worker completed. The join stopped waiting;\n"
                + "the worker was not stopped, and its connection is quarantined for the rest of the\n"
                + "operation and aborted at release.\n")
            .addField(FieldSpec.builder(tenantKey, "key", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(tenantKey, "key")
                .addStatement("this.key = key")
                .build())
            .addMethod(MethodSpec.methodBuilder("key")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(tenantKey)
                .addStatement("return key")
                .build())
            .build();

        return TypeSpec.interfaceBuilder("Outcome")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.SEALED)
            .addTypeVariable(r)
            .addJavadoc("One tenant's {@link #scatter} outcome: exactly one arm per key. The substrate\n"
                + "reports; the caller decides what non-success means (partial data, a request error,\n"
                + "anything between); no outcome is ever dropped silently at this layer.\n")
            .addMethod(keyAccessor)
            .addType(success)
            .addType(failed)
            .addType(timedOut)
            .build();
    }

    /**
     * {@code static DSLContext dslFor(DataFetchingEnvironment env, T tenantKey)}: the one-call
     * routed acquisition emitted fetcher sites use. Resolves the carrier off the GraphQL
     * context and pins the key's connection, wrapping the checked acquisition failure in jOOQ's
     * {@code DataAccessException} so batch rows methods and dispatch surfaces (which declare no
     * checked exceptions) route it through the same redaction contract as any data-access fault.
     */
    private static MethodSpec staticDslFor(ClassName self, TypeName tenantKey) {
        return MethodSpec.methodBuilder("dslFor")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(DSL_CONTEXT)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(tenantKey, "tenantKey")
            .beginControlFlow("try")
            .addStatement("return of(env).dslFor(tenantKey)")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S + tenantKey, e)", DATA_ACCESS_EXCEPTION,
                "Acquiring the routed connection failed for tenant key: ")
            .endControlFlow()
            .addJavadoc("Routed acquisition for emitted fetcher sites: {@link #of} + {@link #dslFor(Object)},\n"
                + "with the checked acquisition failure wrapped unchecked.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n"
                + "@param tenantKey the divined tenant value\n")
            .build();
    }

    /** The global-read sibling of {@link #staticDslDefault}, emitted in multi-tenant builds only. */
    private static MethodSpec staticDslGlobal(ClassName self) {
        return MethodSpec.methodBuilder("dslGlobal")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(DSL_CONTEXT)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .beginControlFlow("try")
            .addStatement("return of(env).dslGlobal()")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S, e)", DATA_ACCESS_EXCEPTION,
                "Acquiring the connection for a global read failed")
            .endControlFlow()
            .addJavadoc("Global-read acquisition for emitted fetcher sites: {@link #of} +\n"
                + "{@link #dslGlobal()}, with the checked acquisition failure wrapped unchecked.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n")
            .build();
    }

    /**
     * {@code RequestTenants}: the request tenant set, an optional default tenant and an optional
     * entity default tenant, one value so the membership invariant lives in its constructor rather
     * than spread over the factory, the instrumentation and the carrier. A final value class with
     * record-style accessors rather than a Java record, because the project's JavaPoet fork does not
     * expose a record builder; the consumer-facing surface (canonical constructor, {@code tenants()},
     * {@code defaultTenant()}, {@code entityDefaultTenant()}, value equality) is the same. The entity
     * default tenant is held resolved: the factories and withers decide what naming nothing
     * separately means, so the stored value needs no "inherit" marker. {@code tenantKey} is the boxed
     * tenant key type, so the class is not generic. Valid Java 17.
     */
    private static TypeSpec requestTenants(ClassName self, ClassName tenantConnections, TypeName tenantKey) {
        var setType = ParameterizedTypeName.get(SET, tenantKey);
        var optionalKey = ParameterizedTypeName.get(OPTIONAL, tenantKey);
        var collectionType = ParameterizedTypeName.get(COLLECTION, tenantKey);
        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(setType, "tenants")
            .addParameter(optionalKey, "defaultTenant")
            .addParameter(optionalKey, "entityDefaultTenant")
            .addStatement("$T<$T> canonical = new $T<>()", LIST, tenantKey, ARRAY_LIST)
            .beginControlFlow("for ($T tenant : $T.requireNonNull(tenants, $S))", tenantKey, OBJECTS, "tenants")
            .addStatement("canonical.add($T.canonicalTenantKey($T.requireNonNull(tenant, $S)))",
                tenantConnections, OBJECTS, "tenant")
            .endControlFlow()
            .addStatement("$T<$T> copy = $T.copyOf(canonical)", SET, tenantKey, SET)
            .addStatement("$T<$T> canonicalDefault = $T.requireNonNull(defaultTenant, $S)"
                + ".map($T::canonicalTenantKey)", OPTIONAL, tenantKey, OBJECTS, "defaultTenant", tenantConnections)
            .beginControlFlow("if (canonicalDefault.isPresent() && !copy.contains(canonicalDefault.get()))")
            .addStatement("throw new $T($S + defaultTenant.get() + $S)", IllegalArgumentException.class,
                "Default tenant '", "' is not in the request tenant set.")
            .endControlFlow()
            .addStatement("$T<$T> canonicalEntityDefault = $T.requireNonNull(entityDefaultTenant, $S)"
                + ".map($T::canonicalTenantKey)", OPTIONAL, tenantKey, OBJECTS, "entityDefaultTenant",
                tenantConnections)
            .beginControlFlow("if (canonicalEntityDefault.isPresent() && !copy.contains(canonicalEntityDefault.get()))")
            .addStatement("throw new $T($S + entityDefaultTenant.get() + $S)", IllegalArgumentException.class,
                "Entity default tenant '", "' is not in the request tenant set.")
            .endControlFlow()
            .addStatement("this.tenants = copy")
            .addStatement("this.defaultTenant = canonicalDefault")
            .addStatement("this.entityDefaultTenant = canonicalEntityDefault")
            .addJavadoc("Every tenant is reduced to the tenant column's form ({@link "
                + tenantConnections.simpleName() + "#canonicalTenantKey})\n"
                + "before the membership check, so two spellings the column stores as one value are one\n"
                + "tenant, and {@link #tenants()}, {@link #defaultTenant()} and\n"
                + "{@link #entityDefaultTenant()} report that form.\n"
                + "@param tenants the tenants this request may touch in this deployment; copied\n"
                + "@param defaultTenant the member of {@code tenants} that serves global reads, or empty\n"
                + "@param entityDefaultTenant the member of {@code tenants} on which {@code _entities}\n"
                + "looks up a {@code @defaultTenant} type's representations, or empty, in which case\n"
                + "every such representation answers {@code null}\n"
                + "@throws IllegalArgumentException when {@code defaultTenant} or\n"
                + "{@code entityDefaultTenant} is present and not in {@code tenants}, or a tenant is not\n"
                + "a value of the tenant column\n")
            .build();
        var twoRoleConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(setType, "tenants")
            .addParameter(optionalKey, "defaultTenant")
            .addStatement("this(tenants, defaultTenant, defaultTenant)")
            .addJavadoc("A request whose entity default tenant is its default tenant.\n"
                + "@param tenants the tenants this request may touch in this deployment; copied\n"
                + "@param defaultTenant the member of {@code tenants} that serves global reads and\n"
                + "{@code @defaultTenant} entities, or empty\n"
                + "@throws IllegalArgumentException when {@code defaultTenant} is present and not in\n"
                + "{@code tenants}, or a tenant is not a value of the tenant column\n")
            .build();
        var of = MethodSpec.methodBuilder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(self)
            .addParameter(collectionType, "tenants")
            .addStatement("return new $T($T.copyOf($T.requireNonNull(tenants, $S)), $T.empty())",
                self, SET, OBJECTS, "tenants", OPTIONAL)
            .addJavadoc("A request tenant set with no default tenant: global tables read the default\n"
                + "source, and {@code @defaultTenant} entities answer {@code null} unless\n"
                + "{@link #withEntityDefault} names a tenant for them.\n"
                + "@param tenants the tenants this request may touch in this deployment\n")
            .build();
        var withDefault = MethodSpec.methodBuilder("withDefault")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(self)
            .addParameter(collectionType, "tenants")
            .addParameter(tenantKey, "defaultTenant")
            .addStatement("return new $T($T.copyOf($T.requireNonNull(tenants, $S)), $T.of(defaultTenant))",
                self, SET, OBJECTS, "tenants", OPTIONAL)
            .addJavadoc("A request tenant set whose member {@code defaultTenant} serves graphitron's reads\n"
                + "of global tables, on that tenant's connection and under its session mount, and is also\n"
                + "the entity default tenant unless {@link #withEntityDefault} or\n"
                + "{@link #withoutEntityDefault} says otherwise.\n"
                + "@param tenants the tenants this request may touch in this deployment\n"
                + "@param defaultTenant the tenant whose database answers global reads; must be in\n"
                + "{@code tenants}\n"
                + "@throws IllegalArgumentException when {@code defaultTenant} is not in {@code tenants}\n")
            .build();
        var withEntityDefault = MethodSpec.methodBuilder("withEntityDefault")
            .addModifiers(Modifier.PUBLIC)
            .returns(self)
            .addParameter(tenantKey, "tenant")
            .addStatement("return new $T(tenants, defaultTenant, $T.of(tenant))", self, OPTIONAL)
            .addJavadoc("This request with {@code tenant} as its entity default tenant: {@code _entities}\n"
                + "looks up a {@code @defaultTenant} type's representations on it. The tenant set and the\n"
                + "default tenant are unchanged.\n"
                + "@param tenant the entity default tenant; must be in {@link #tenants()}\n"
                + "@throws IllegalArgumentException when {@code tenant} is not in the tenant set\n")
            .build();
        var withoutEntityDefault = MethodSpec.methodBuilder("withoutEntityDefault")
            .addModifiers(Modifier.PUBLIC)
            .returns(self)
            .addStatement("return new $T(tenants, defaultTenant, $T.empty())", self, OPTIONAL)
            .addJavadoc("This request with no entity default tenant: every representation of a\n"
                + "{@code @defaultTenant} type answers {@code null}, without an error and without opening a\n"
                + "connection for it. The tenant set and the default tenant are unchanged, so global reads\n"
                + "still use the default tenant.\n")
            .build();
        var tenantsAccessor = MethodSpec.methodBuilder("tenants")
            .addModifiers(Modifier.PUBLIC)
            .returns(setType)
            .addStatement("return tenants")
            .addJavadoc("The request tenant set, immutable.\n")
            .build();
        var defaultTenantAccessor = MethodSpec.methodBuilder("defaultTenant")
            .addModifiers(Modifier.PUBLIC)
            .returns(optionalKey)
            .addStatement("return defaultTenant")
            .addJavadoc("The default tenant, or empty when the request names none.\n")
            .build();
        var entityDefaultTenantAccessor = MethodSpec.methodBuilder("entityDefaultTenant")
            .addModifiers(Modifier.PUBLIC)
            .returns(optionalKey)
            .addStatement("return entityDefaultTenant")
            .addJavadoc("The entity default tenant, on which {@code _entities} looks up a\n"
                + "{@code @defaultTenant} type's representations, or empty when the request names none.\n")
            .build();
        var equals = MethodSpec.methodBuilder("equals")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addParameter(Object.class, "other")
            .addStatement("return other instanceof $T that && tenants.equals(that.tenants)"
                + " && defaultTenant.equals(that.defaultTenant)"
                + " && entityDefaultTenant.equals(that.entityDefaultTenant)", self)
            .build();
        var hashCode = MethodSpec.methodBuilder("hashCode")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(int.class)
            .addStatement("return $T.hash(tenants, defaultTenant, entityDefaultTenant)", OBJECTS)
            .build();
        var toString = MethodSpec.methodBuilder("toString")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(String.class)
            .addStatement("return $S + tenants + $S + defaultTenant + $S + entityDefaultTenant + $S",
                REQUEST_TENANTS_CLASS_NAME + "[tenants=", ", defaultTenant=", ", entityDefaultTenant=", "]")
            .build();
        return TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("The request's tenancy, passed to {@code Graphitron.newOwnedExecutionInput}: the request\n"
                + "tenant set, the tenants the request may touch in this deployment, and optionally a\n"
                + "<em>default tenant</em>, one member of the set whose database answers graphitron's reads\n"
                + "of global tables (tables without the tenant column) on that tenant's connection and\n"
                + "under its session mount. Writes to global tables and {@code @service} calls keep the\n"
                + "runtime's default source either way. A second role, the <em>entity default tenant</em>,\n"
                + "is the optional member on which {@code _entities} looks up the representations of a\n"
                + "{@code @defaultTenant} type; it is the default tenant unless {@link #withEntityDefault}\n"
                + "or {@link #withoutEntityDefault} names another or none, and with none every such\n"
                + "representation answers {@code null}. Build one with {@link #of} or\n"
                + "{@link #withDefault}; a default or entity default tenant outside the set is refused\n"
                + "here.\n")
            .addField(FieldSpec.builder(setType, "tenants", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(optionalKey, "defaultTenant", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(optionalKey, "entityDefaultTenant", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(constructor)
            .addMethod(twoRoleConstructor)
            .addMethod(of)
            .addMethod(withDefault)
            .addMethod(withEntityDefault)
            .addMethod(withoutEntityDefault)
            .addMethod(tenantsAccessor)
            .addMethod(defaultTenantAccessor)
            .addMethod(entityDefaultTenantAccessor)
            .addMethod(equals)
            .addMethod(hashCode)
            .addMethod(toString)
            .build();
    }

    /** The default-source sibling of {@link #staticDslFor}. */
    private static MethodSpec staticDslDefault(ClassName self) {
        return MethodSpec.methodBuilder("dslDefault")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(DSL_CONTEXT)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .beginControlFlow("try")
            .addStatement("return of(env).dslDefault()")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S, e)", DATA_ACCESS_EXCEPTION,
                "Acquiring the default-source connection failed")
            .endControlFlow()
            .addJavadoc("Default-source acquisition for emitted fetcher sites: {@link #of} +\n"
                + "{@link #dslDefault()}, with the checked acquisition failure wrapped unchecked.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n")
            .build();
    }

    /**
     * {@code static String loaderName(DataFetchingEnvironment env)}: the path-derived DataLoader
     * name every multi-tenant registration site reads, so the naming recipe cannot drift between
     * sites. Path keys only (list indices stripped), joined by {@code "/"}, exactly the
     * single-tenant inline form.
     */
    private static MethodSpec loaderName() {
        return MethodSpec.methodBuilder("loaderName")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("return String.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())", "/")
            .addJavadoc("The path-derived DataLoader name: named path segments joined by {@code \"/\"}.\n"
                + "The single naming seam for every multi-tenant loader registration site.\n")
            .build();
    }

    /**
     * {@code static String tenantLoaderName(DataFetchingEnvironment env)}: the tenant-partitioned
     * loader name for fields inheriting a divined tenant. Load-bearing, not cosmetic: a batch
     * loader resolves one {@code DSLContext} from the environment captured at loader creation,
     * so a tenant-mixed loader would execute every key against the first key's tenant. The
     * tenant segment is an opaque partition key, never parsed back (the captured environment
     * carries the typed tenant).
     */
    private static MethodSpec tenantLoaderName() {
        return MethodSpec.methodBuilder("tenantLoaderName")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("return tenantLoaderName(env, env.getLocalContext())")
            .addJavadoc("The tenant-partitioned DataLoader name for a field inheriting a divined tenant:\n"
                + "{@link #tenantLoaderName(DataFetchingEnvironment, Object)} over the handed-down\n"
                + "tenant.\n")
            .build();
    }

    /**
     * {@code static String tenantLoaderName(DataFetchingEnvironment env, Object tenant)}: the
     * naming recipe's one home. The one-argument form passes the handed-down tenant; a field that
     * reads its tenant off the parent row passes that value, so its loaders partition per parent
     * tenant the same way.
     */
    private static MethodSpec tenantLoaderNameFor() {
        return MethodSpec.methodBuilder("tenantLoaderName")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(Object.class, "tenant")
            .addStatement("return loaderName(env) + $S + tenant", " tenant:")
            .addJavadoc("The tenant-partitioned DataLoader name: {@link #loaderName} plus an opaque\n"
                + "tenant segment (never parsed back), so each loader batch stays tenant-homogeneous\n"
                + "and its captured environment routes the right source. The separator contains\n"
                + "characters no GraphQL path segment can, so the segment cannot collide with a path\n"
                + "suffix.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n"
                + "@param tenant the tenant the loader's batch runs against\n")
            .build();
    }

    /**
     * {@code static TenantConnections of(DataFetchingEnvironment env)}: resolves the per-operation
     * carrier the execution instrumentation stashed in the GraphQL context, on both topologies.
     * Emitted fetchers route every owned acquisition through this, so an operation that did not
     * run through graphitron-owned acquisition fails loudly before any SQL instead of silently
     * targeting the wrong database (or binding a null).
     */
    private static MethodSpec ofEnvironment(ClassName self) {
        return MethodSpec.methodBuilder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(self)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("$T carrier = env.getGraphQlContext().get($T.class)", self, self)
            .beginControlFlow("if (carrier == null)")
            .addStatement("throw new $T($S)", IllegalStateException.class,
                "No " + TENANT_CONNECTIONS_CLASS_NAME + " in the GraphQL context: the owned-connection path"
                    + " resolves every connection through the per-operation carrier, so the operation must be"
                    + " built with Graphitron.newOwnedExecutionInput(...) and executed through"
                    + " GraphitronRuntime.newGraphQL(schema).")
            .endControlFlow()
            .addStatement("return carrier")
            .addJavadoc("The per-operation carrier the execution instrumentation stashed in the GraphQL\n"
                + "context. Fails loudly when the operation did not run through graphitron-owned\n"
                + "acquisition; owned fetch paths never fall back to an unrouted connection.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n")
            .build();
    }

    /**
     * {@code static <handleType> sessionHandle(DSLContext dsl, String fieldCoordinate)}: the
     * guarded handle read every {@code $session}-bound call site routes through. The handle is
     * written into the pinned connection's per-{@code Configuration} data map at mint, so a
     * missing entry means graphitron never mounted this connection: the operation ran on a
     * caller-supplied {@code DSLContext} (the escape-hatch factory), whose pooled provider makes
     * identity not merely unmounted but unmountable. The build cannot see which factory the
     * caller used, so this is the one {@code $session} failure that is runtime rather than
     * build-time, and it fails loudly in {@link #ofEnvironment of(env)}'s idiom instead of
     * binding a null into a service parameter meant to carry mounted identity.
     */
    private static MethodSpec sessionHandleAccessor(TypeName handleType) {
        return MethodSpec.methodBuilder("sessionHandle")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(handleType)
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(String.class, "fieldCoordinate")
            .addStatement("Object handle = dsl.configuration().data($S)", SessionHooks.HANDLE_DATA_KEY)
            .beginControlFlow("if (handle == null)")
            .addStatement("throw new $T($S + fieldCoordinate + $S)", IllegalStateException.class,
                "Field '",
                "' binds a parameter to " + ArgMappingSigil.SESSION_LITERAL + ", but this DSLContext"
                    + " carries no mounted session handle: graphitron never mounted this connection."
                    + " Only owned acquisition mounts, so the operation must be built with"
                    + " Graphitron.newOwnedExecutionInput(...) and executed through"
                    + " GraphitronRuntime.newGraphQL(schema); a caller-supplied DSLContext"
                    + " (Graphitron.newExecutionInput(dsl, ...)) cannot carry mounted identity.")
            .endControlFlow()
            .addStatement("return ($T) handle", handleType)
            .addJavadoc("The mounted session handle riding {@code dsl}'s per-{@code Configuration} data map,\n"
                + "written at mint by the owned acquisition path. Fails loudly when absent: only owned\n"
                + "acquisition mounts, so a missing handle means the operation ran on a caller-supplied\n"
                + "DSLContext (the escape hatch), which cannot carry mounted identity.\n"
                + "@param dsl the call site's resolved per-connection context\n"
                + "@param fieldCoordinate the {@code $$session}-binding field, for the failure message\n")
            .build();
    }

    /**
     * {@code static T namedTenant(Object... candidates)}: folds every runtime value of a field's
     * tenant bindings into the one tenant they name, or {@code null} when they name none (every
     * slot absent, or every list it reads through empty). Collections flatten; all non-null values
     * must agree once reduced to the column's form; a value the column cannot store is a
     * request-level error. The absent-tolerant
     * fold behind {@code divinedTenant}, called directly where an empty request has its own
     * answer.
     */
    private static MethodSpec namedTenant(TypeName tenantKey) {
        return MethodSpec.methodBuilder("namedTenant")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(Object[].class, "candidates")
            .varargs()
            .addStatement("$T key = null", tenantKey)
            .beginControlFlow("for (Object candidate : candidates)")
            .addStatement("key = agreeOnTenant(key, candidate)")
            .endControlFlow()
            .addStatement("return key")
            .addJavadoc("Folds the runtime values of a field's tenant bindings into the one tenant they\n"
                + "name, or {@code null} when they name none: collections flatten, every non-null value is\n"
                + "reduced to the tenant column's form ({@link #canonicalTenantKey}) and all must agree,\n"
                + "and a value the column cannot store is a request-level error before any SQL.\n"
                + "@param candidates each bound slot's runtime value (a value, a collection of values, or {@code null})\n")
            .build();
    }

    /**
     * {@code static T divinedTenant(Object... candidates)}: {@code namedTenant}, where a fold
     * naming no tenant is a request-level error before any SQL.
     */
    private static MethodSpec divinedTenant(TypeName tenantKey) {
        return MethodSpec.methodBuilder("divinedTenant")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(Object[].class, "candidates")
            .varargs()
            .addStatement("$T key = namedTenant(candidates)", tenantKey)
            .beginControlFlow("if (key == null)")
            .addComment("Absent binding value: a request-level error before any SQL, same family as the")
            .addComment("unknown-tenant acquisition failure.")
            .addStatement("throw new $T($S)", NO_SUCH_ELEMENT,
                "The tenant binding value is absent; cannot route the operation to a tenant database.")
            .endControlFlow()
            .addStatement("return key")
            .addJavadoc("Folds the runtime values of a field's tenant bindings into the one divined key:\n"
                + "collections flatten, all non-null values must agree once reduced to the tenant\n"
                + "column's form, and an absent value or one the column cannot store is a request-level\n"
                + "error before any SQL. A wire-form candidate (a decoded node-id segment, an ID\n"
                + "argument) coerces through the column's data type, as the generated SQL binds it.\n"
                + "@param candidates each bound slot's runtime value (a value, a collection of values, or {@code null})\n")
            .build();
    }

    /**
     * {@code public static K tenantKeyOrNull(Object value)}: the tenant key a value names, or
     * {@code null} when it names none. The value takes the column trip of the generated
     * {@code ColumnValues.coerce} over the representative tenant column's {@code DataType}: coerced
     * to the column's Java form, put through its converter to the database value and back, so
     * every spelling the column stores as one database value comes out as one key, and a number
     * that would wrap or exceed the column's precision names no tenant. The non-throwing half the
     * lookups use, where an id whose tenant does not decode answers {@code null} like any
     * malformed id.
     */
    private static MethodSpec tenantKeyOrNull(TypeName tenantKey, ClassName columnValues) {
        return MethodSpec.methodBuilder("tenantKeyOrNull")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(Object.class, "value")
            .addStatement("return $T.coerce(TENANT_COLUMN_TYPE, value)", columnValues)
            .addJavadoc("The tenant key a value names, or {@code null} when it names none: {@link #canonicalTenantKey}\n"
                + "without the throw.\n"
                + "@param value a tenant value in any form the column's data type coerces\n"
                + "@return the key in the column's canonical Java form, or {@code null}\n")
            .build();
    }

    /**
     * {@code public static K canonicalTenantKey(Object value)}: the one tenant key equality, the
     * throwing layer over {@code tenantKeyOrNull}. Public so a consumer can key its own tenant
     * state by the same equality.
     *
     * <p>A {@code null} input, and a value the column cannot store (the coercion or the converter
     * gives {@code null} or throws {@code IllegalArgumentException} or {@code DataTypeException},
     * or a number would wrap or exceed the column's precision), throw
     * {@code IllegalArgumentException} naming it. Any other converter exception propagates: it is
     * a fault in the consumer's converter, not a fact about the value. A value the column binds
     * by coercion ({@code "1.5"} on an integer column) names the tenant it coerces to, because
     * the key follows the bind. Configured keys pass through here too, so a configured key that
     * would wrap is refused when the configuration is built: it is a misconfiguration.
     */
    private static MethodSpec canonicalTenantKey(TypeName tenantKey) {
        return MethodSpec.methodBuilder("canonicalTenantKey")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(Object.class, "value")
            .addStatement("$T canonical = tenantKeyOrNull(value)", tenantKey)
            .beginControlFlow("if (canonical == null)")
            .addStatement("throw new $T($S + value + $S)", IllegalArgumentException.class,
                "Tenant value '", "' is not a value of the tenant column.")
            .endControlFlow()
            .addStatement("return canonical")
            .addJavadoc("The tenant key a value names: the value coerced through the tenant column's data\n"
                + "type, then put through the column's converter to its database value and back. Two values\n"
                + "the column stores as one database value give one key, so use this to key your own tenant\n"
                + "state by the equality graphitron routes with. A number the column would store as a\n"
                + "different number (one that overflows the column's type or precision) names no tenant.\n"
                + "@param value a tenant value in any form the column's data type coerces\n"
                + "@return the key in the column's canonical Java form\n"
                + "@throws IllegalArgumentException when the value is {@code null} or the column cannot store it\n")
            .build();
    }

    /**
     * {@code private static K tenantKeyOf(Object value)}: one client-sourced tenant value reduced
     * to its canonical key, shared by the routing fold and the fan-out narrowing so a value names
     * the same tenant whichever reads it. A value the tenant column cannot store is the client's
     * mistake, raised as the generated client-error type before the request tenant set is
     * consulted, so it reveals nothing about which tenants exist, and before a narrowed domain is
     * intersected, so it cannot empty one.
     */
    private static MethodSpec tenantKeyOf(TypeName tenantKey, ClassName clientException) {
        return MethodSpec.methodBuilder("tenantKeyOf")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(Object.class, "value")
            .beginControlFlow("try")
            .addStatement("return canonicalTenantKey(value)")
            .nextControlFlow("catch ($T e)", IllegalArgumentException.class)
            .addStatement("throw new $T($S + value + $S)", clientException,
                "Tenant value '", "' is not a value of the tenant column.")
            .endControlFlow()
            .build();
    }

    /**
     * The recursive agree-fold behind {@code divinedTenant}: flattens collections, reduces each
     * leaf to its canonical key, rejects disagreement. Disagreement is the caller's mistake (a
     * batch whose ids decode to two tenants, or two bound arguments naming different ones), and
     * one statement on one connection has no correct execution for it, so it raises the generated
     * client-error type and the client reads which tenants disagreed rather than a redacted
     * reference.
     */
    private static MethodSpec divinedTenantAgree(TypeName tenantKey, ClassName clientException) {
        var collection = ClassName.get("java.util", "Collection");
        return MethodSpec.methodBuilder("agreeOnTenant")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(tenantKey, "current")
            .addParameter(Object.class, "candidate")
            .beginControlFlow("if (candidate == null)")
            .addStatement("return current")
            .endControlFlow()
            .beginControlFlow("if (candidate instanceof $T<?> values)", collection)
            .beginControlFlow("for (Object value : values)")
            .addStatement("current = agreeOnTenant(current, value)")
            .endControlFlow()
            .addStatement("return current")
            .endControlFlow()
            .addStatement("$T key = tenantKeyOf(candidate)", tenantKey)
            .beginControlFlow("if (current != null && !current.equals(key))")
            .addStatement("throw new $T($S + current + $S + key + $S)", clientException,
                "Tenant bindings disagree within one operation: '", "' vs '",
                "'. One call writes or reads one tenant database, so every tenant value it names"
                    + " must be the same.")
            .endControlFlow()
            .addStatement("return key")
            .build();
    }

    /**
     * {@code static Object tenantSlot(Object container, String... path)}: reads a nested
     * input-object slot by the exact key path computed at build time from the slot's column
     * mapping (never a name search). Null-safe at every step; a list-shaped level maps the
     * remaining path over its elements (the divined-tenant fold flattens the result).
     */
    private static MethodSpec tenantSlot() {
        var list = ClassName.get("java.util", "List");
        var arrayList = ClassName.get("java.util", "ArrayList");
        var arrays = ClassName.get("java.util", "Arrays");
        var collection = ClassName.get("java.util", "Collection");
        return MethodSpec.methodBuilder("tenantSlot")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(Object.class, "container")
            .addParameter(String[].class, "path")
            .varargs()
            .addStatement("Object current = container")
            .beginControlFlow("for (int i = 0; i < path.length; i++)")
            .beginControlFlow("if (current instanceof $T<?> values)", collection)
            .addComment("List-shaped level (e.g. a batch input): read the remaining path off every element;")
            .addComment("the divined-tenant fold flattens and equality-guards the results.")
            .addStatement("$T<Object> out = new $T<>()", list, arrayList)
            .addStatement("String[] rest = $T.copyOfRange(path, i, path.length)", arrays)
            .beginControlFlow("for (Object value : values)")
            .addStatement("out.add(tenantSlot(value, rest))")
            .endControlFlow()
            .addStatement("return out")
            .endControlFlow()
            .beginControlFlow("if (!(current instanceof $T<?, ?> map))", MAP)
            .addStatement("return null")
            .endControlFlow()
            .addStatement("current = map.get(path[i])")
            .endControlFlow()
            .addStatement("return current")
            .addJavadoc("Reads a nested input-object slot by the exact key path the build computed from the\n"
                + "slot's column mapping. Null-safe at every step; a list-shaped level maps the remaining\n"
                + "path over its elements.\n"
                + "@param container the outer argument value (a {@code Map}, a {@code List} of maps, or {@code null})\n"
                + "@param path the build-time key path from the container down to the bound slot\n")
            .build();
    }

    /**
     * {@code SessionMountException}: what {@code PinnedConnection.acquire} turns a throwing mount
     * into. A request-scoped fault, unlike the field-scoped {@code GraphitronClientException}: the
     * carrier remembers the first one per operation, and the instrumentation throws it out of
     * {@code GraphQL.execute} so the consumer can answer the request as a whole.
     */
    private static TypeSpec sessionMountException(ClassName self) {
        return TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .superclass(RuntimeException.class)
            .addJavadoc("The database rejected this request's session identity: the configured\n"
                + "{@code <sessionState>} mount threw. Thrown out of {@code GraphQL.execute} on the\n"
                + "owned-connection path in place of a result, since no field can run without identity.\n"
                + "The mount's own exception is the {@linkplain #getCause() cause}, so its SQLState stays\n"
                + "inspectable; the message carries none of it.\n")
            .addField(FieldSpec.builder(long.class, "serialVersionUID",
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("1L")
                .build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Throwable.class, "cause")
                .addStatement("super($S, cause)", "The session identity mount was rejected")
                .addJavadoc("@param cause what the mount threw\n")
                .build())
            .build();
    }

    /**
     * Emits the generated hook class: one final class with static {@code mount} and
     * {@code unmount} methods that call the consumer's resolved methods directly (nothing is
     * registered, nothing is dispatched polymorphically; the call site is a direct static
     * invocation). Called only when {@link SessionHooks#emitsHookImplementation()} holds, the
     * single membership fact the emit plan also reads. This class is where the provider-free
     * {@code Configuration} is built, from the connection and the resolved source's dialect and
     * settings (so a consumer's schema mapping reaches their own mount method, and the
     * transaction-demarcation provider structurally cannot), and where the payload is spread into
     * the mount method's own declaration order; {@code PinnedConnection} stays free of both. In
     * multi-tenant builds the generated {@code mount} also takes the {@code Optional<K>} tenant it
     * is mounting for, spread into the consumer's tenant slot when the method declares one
     * ({@link SessionHooks#tenantSlot()}) and dropped otherwise.
     */
    private static TypeSpec sessionHookImpl(SessionHooks sessionHooks, TypeName tenantKey, boolean multiTenant) {
        MethodRef.StaticOnly mountRef = sessionHooks.mountRef().orElseThrow(() -> new IllegalStateException(
            "no session hook implementation exists for the not-configured arm; gate on emitsHookImplementation()"));
        TypeName handleType = sessionHooks instanceof SessionHooks.Handled handled ? handled.handleType() : null;
        var payload = payloadParams(sessionHooks);

        var mountBuilder = MethodSpec.methodBuilder("mount")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(handleType == null ? TypeName.VOID : handleType)
            .addParameter(CONNECTION, "connection")
            .addParameter(SQL_DIALECT, "dialect")
            .addParameter(SETTINGS, "settings");
        if (multiTenant) {
            mountBuilder.addParameter(ParameterizedTypeName.get(OPTIONAL, tenantKey), MOUNTED_TENANT);
        }
        for (var p : payload) {
            mountBuilder.addParameter(p.javaType(), p.name());
        }
        var mountCall = hookCallArgs(mountRef);
        if (handleType != null) {
            mountBuilder.addStatement("return $T.$L($L)",
                ClassName.bestGuess(mountRef.className()), mountRef.methodName(), mountCall);
        } else {
            mountBuilder.addStatement("$T.$L($L)",
                ClassName.bestGuess(mountRef.className()), mountRef.methodName(), mountCall);
        }
        var mount = mountBuilder
            .addJavadoc("Mounts the caller's identity onto the freshly pinned {@code connection} by calling\n"
                + "{@code " + mountRef.className() + "#" + mountRef.methodName() + "} directly, spreading the\n"
                + "typed payload in that method's own declaration order"
                + (handleType == null ? "" : " and returning its handle") + ".\n"
                + (!multiTenant ? ""
                    : sessionHooks.tenantSlot().isPresent()
                        ? "{@code " + MOUNTED_TENANT + "} is the tenant this connection is mounted for, passed to the method's\n"
                            + "tenant slot: the routed key, or {@code Optional.empty()} for the default source.\n"
                        : "{@code " + MOUNTED_TENANT + "} is the tenant this connection is mounted for; the method declares no\n"
                            + "tenant slot, so it is not passed on.\n")
                + "Runs before any operation SQL, with autocommit asserted by the runtime: the mount is\n"
                + "its own committed transaction, so a later transaction's rollback cannot revert it, and\n"
                + "what the method sets must be <em>session-scoped</em> state in the database's own\n"
                + "vocabulary (transaction-scoped storage is gone by this call's own implicit commit).\n"
                + "A thrown exception fails the request closed: the connection is evicted, never pooled.\n"
                + (seamKind(mountRef) == ParamSource.SessionSeam.Kind.CONFIGURATION
                    ? "The {@code Configuration} handed to the method is provider-free and carries the\n"
                        + "resolved source's dialect and settings, so the consumer's schema and render\n"
                        + "mapping apply to their own SQL here as everywhere else.\n"
                    : "The method declared a raw JDBC {@code Connection} seam, so the pinned connection is\n"
                        + "passed directly.\n"))
            .build();

        MethodSpec unmount = null;
        if (sessionHooks.unmountRef().isPresent()) {
            MethodRef.StaticOnly unmountRef = sessionHooks.unmountRef().orElseThrow();
            boolean takesHandle = unmountRef.params().stream()
                .anyMatch(p -> p.source() instanceof ParamSource.SessionHandle);
            var unmountBuilder = MethodSpec.methodBuilder("unmount")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(CONNECTION, "connection")
                .addParameter(SQL_DIALECT, "dialect")
                .addParameter(SETTINGS, "settings");
            if (takesHandle) {
                unmountBuilder.addParameter(handleType, "handle");
            }
            unmount = unmountBuilder
                .addStatement("$T.$L($L)",
                    ClassName.bestGuess(unmountRef.className()), unmountRef.methodName(),
                    hookCallArgs(unmountRef))
                .addJavadoc("Unmounts the identity {@code mount} mounted by calling\n"
                    + "{@code " + unmountRef.className() + "#" + unmountRef.methodName() + "} directly"
                    + (takesHandle ? ", bound to\nthe handle mount returned" : "") + ". Fires at release on\n"
                    + "every completion path, outside any transaction (the runtime rolls back anything the\n"
                    + "operation left open and asserts autocommit first), so the clears take effect\n"
                    + "immediately. The method's return value, if any, is discarded. If this throws, the\n"
                    + "runtime evicts the physical connection rather than returning tainted state to the\n"
                    + "pool.\n")
                .build();
        }

        var builder = TypeSpec.classBuilder(SESSION_HOOK_IMPL_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Generated session-hook calls for the {@code <sessionState>} method pair: static\n"
                + "{@code mount}"
                + (unmount == null ? "" : " and {@code unmount}") + " called directly by the connection\n"
                + "lifecycle, resolved at build time from the configured {@code fqcn#method} references.\n"
                + "Nothing is registered and nothing is dispatched polymorphically; a misnamed method is a\n"
                + "build failure, not a runtime surprise.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(mount);
        if (unmount != null) {
            builder.addMethod(unmount);
        }
        return builder.build();
    }

    /** The resolved seam kind of a hook method (exactly one seam parameter by construction). */
    private static ParamSource.SessionSeam.Kind seamKind(MethodRef.StaticOnly ref) {
        return ref.params().stream()
            .map(MethodRef.Param::source)
            .filter(s -> s instanceof ParamSource.SessionSeam)
            .map(s -> ((ParamSource.SessionSeam) s).kind())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "resolved hook method carries no seam parameter: " + ref.className() + "#" + ref.methodName()));
    }

    /**
     * The argument list for the direct call into a consumer hook method, in the method's own
     * declaration order: the seam parameter becomes the provider-free {@code Configuration}
     * (or the raw connection, per the {@link ParamSource.SessionSeam.Kind} decided once at
     * reflection), payload parameters read the generated method's same-named locals, the mount's
     * tenant slot reads {@value #MOUNTED_TENANT}, and the unmount's handle parameter reads {@code handle}.
     */
    private static CodeBlock hookCallArgs(MethodRef.StaticOnly ref) {
        var args = CodeBlock.builder();
        boolean first = true;
        for (var p : ref.params()) {
            if (!first) {
                args.add(", ");
            }
            first = false;
            switch (p.source()) {
                case ParamSource.SessionSeam seam -> {
                    if (seam.kind() == ParamSource.SessionSeam.Kind.CONFIGURATION) {
                        args.add("$T.using(connection, dialect, settings).configuration()", DSL);
                    } else {
                        args.add("connection");
                    }
                }
                case ParamSource.Context ignored -> args.add("$L", p.name());
                case ParamSource.SessionHandle ignored -> args.add("handle");
                case ParamSource.SessionTenant ignored -> args.add(MOUNTED_TENANT);
                default -> throw new IllegalStateException(
                    "unexpected hook parameter source " + p.source().getClass().getSimpleName()
                        + " on " + ref.className() + "#" + ref.methodName());
            }
        }
        return args.build();
    }
}
