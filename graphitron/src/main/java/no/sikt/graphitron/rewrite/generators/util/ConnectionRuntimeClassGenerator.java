package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.MethodRef;
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
    /** The per-operation tenant-keyed connection carrier. */
    public static final String TENANT_CONNECTIONS_CLASS_NAME = "TenantConnections";
    /** The runtime's nested source triple ({@code DataSource}, dialect, jOOQ {@code Settings}). */
    public static final String SOURCE_CLASS_NAME = "Source";

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
    private static final ClassName THREAD_LOCAL = ClassName.get("java.lang", "ThreadLocal");
    private static final TypeName OBJECT_KEY = ClassName.get(Object.class);
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DATA_FETCHING_ENVIRONMENT = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName DATA_ACCESS_EXCEPTION = ClassName.get("org.jooq.exception", "DataAccessException");
    private static final ClassName DATA_FETCHER_RESULT = ClassName.get("graphql.execution", "DataFetcherResult");
    private static final ClassName GRAPHQL_ERROR = ClassName.get("graphql", "GraphQLError");
    private static final ClassName GRAPHQL_ERROR_BUILDER = ClassName.get("graphql", "GraphqlErrorBuilder");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");
    private static final ClassName UUID_CLASS = ClassName.get("java.util", "UUID");

    /** The graphQLContext key the request's fan-out tenant collection is published under. */
    public static final String FAN_OUT_TENANTS_KEY_FIELD = "FAN_OUT_TENANTS_KEY";
    /** The literal value of the emitted {@code FAN_OUT_TENANTS_KEY} constant (also written by the factory). */
    public static final String FAN_OUT_TENANTS_KEY_VALUE = "no.sikt.graphitron.request.fanOutTenants";
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
        return generate(outputPackage, sessionHooks, null);
    }

    /**
     * Canonical form carrying the divined tenant key type. {@code tenantKeyType} is the tenant
     * Java type read off the jOOQ catalog's tenant column when {@code <tenantColumn>} is
     * configured, or {@code null} for single-tenant builds. A configured type replaces the
     * erased {@code Object} on every tenant-keyed surface (the constructor map, the keyed
     * acquisition, the per-operation carrier), so a consumer wiring a map keyed with the wrong
     * type is a compile error rather than a first-request lookup miss.
     */
    public static List<TypeSpec> generate(String outputPackage, SessionHooks sessionHooks,
                                          TypeName tenantKeyType) {
        TypeName tenantKey = tenantKeyType == null
            ? OBJECT_KEY
            : (tenantKeyType.isPrimitive() ? tenantKeyType.box() : tenantKeyType);
        String schemaPackage = outputPackage + ".schema";
        var sessionHookImpl = ClassName.get(schemaPackage, SESSION_HOOK_IMPL_CLASS_NAME);
        var pinnedConnection = ClassName.get(schemaPackage, PINNED_CONNECTION_CLASS_NAME);
        var runtime = ClassName.get(schemaPackage, RUNTIME_CLASS_NAME);
        var tenantConnections = ClassName.get(schemaPackage, TENANT_CONNECTIONS_CLASS_NAME);
        var instrumentation = ClassName.get(schemaPackage, GraphitronConnectionInstrumentationGenerator.CLASS_NAME);
        var provider = ClassName.get(schemaPackage, GraphitronTransactionProviderGenerator.CLASS_NAME);
        var commitPolicy = provider.nestedClass(GraphitronTransactionProviderGenerator.COMMIT_POLICY_ENUM_NAME);

        boolean multiTenant = tenantKeyType != null;

        var units = new ArrayList<TypeSpec>();
        units.add(pinnedConnection(pinnedConnection, sessionHookImpl, sessionHooks, multiTenant));
        units.add(runtime(runtime, pinnedConnection, instrumentation, sessionHooks, tenantKey, multiTenant));
        units.add(tenantConnections(tenantConnections, runtime, pinnedConnection, provider, commitPolicy, tenantKey,
            multiTenant, sessionHooks));
        if (sessionHooks.emitsHookImplementation()) {
            units.add(sessionHookImpl(sessionHooks));
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
     */
    private static TypeSpec pinnedConnection(ClassName pinnedConnection, ClassName sessionHookImpl,
            SessionHooks sessionHooks, boolean multiTenant) {
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
            .addParameter(SETTINGS, "settings")
            .addParameter(EXECUTOR, "abortExecutor");
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
            String args = mountCallArgs(sessionHooks, payload);
            if (handleType != null) {
                acquireBuilder.addStatement("handle = $T.mount($L)", sessionHookImpl, args);
            } else {
                acquireBuilder.addStatement("$T.mount($L)", sessionHookImpl, args);
            }
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
                    + "evicts the connection and propagates before any operation SQL runs.\n"
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
     * resolved source's dialect and settings, then the payload in the mount method's own
     * declaration order (the one call site that invokes the consumer's method positionally).
     */
    private static String mountCallArgs(SessionHooks sessionHooks, List<MethodRef.Param.Typed> payload) {
        var args = new StringBuilder("connection, dialect, settings");
        for (var p : payload) {
            args.append(", ").append(p.name());
        }
        return args.toString();
    }

    /**
     * {@code abort()}: the straggler release path. A scatter worker past its deadline may still be
     * mid-statement on this connection, so neither the disconnect hook (a second concurrent user of
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
            .addJavadoc("Evicts the connection without running the disconnect hook, for a connection whose\n"
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
     * Mojo. No hook state lives here: the mount/unmount choice is emitted, not held.
     */
    private static TypeSpec runtime(ClassName runtime, ClassName pinnedConnection, ClassName instrumentation,
                                    SessionHooks sessionHooks, TypeName tenantKey, boolean multiTenant) {
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
            .addStatement("this.defaultSource = $T.requireNonNull(defaultSource, $S)", OBJECTS, "defaultSource")
            .addStatement("this.sourcesByTenant = new $T<>($T.requireNonNull(sourcesByTenant, $S))",
                LINKED_HASH_MAP, OBJECTS, "sourcesByTenant");
        if (multiTenant) {
            canonicalBuilder
                .addStatement("this.fanOutExecutor = $T.requireNonNull(fanOutExecutor, $S)", OBJECTS, "fanOutExecutor")
                .addStatement("this.fanOutTimeout = $T.requireNonNull(fanOutTimeout, $S)", OBJECTS, "fanOutTimeout")
                .beginControlFlow("if (fanOutTimeout.isZero() || fanOutTimeout.isNegative())")
                .addStatement("throw new $T($S + fanOutTimeout)", IllegalArgumentException.class,
                    "fanOutTimeout must be positive, got: ")
                .endControlFlow();
        }
        var canonicalConstructor = canonicalBuilder
            .addJavadoc("Builds the runtime over a default source (untenanted / single-tenant SQL) and a\n"
                + "per-tenant source map for database-per-tenant routing. Each {@link Source} carries its\n"
                + "own {@code DataSource}, dialect, and jOOQ {@code Settings}, so a consumer's schema or\n"
                + "render mapping reaches every context graphitron mints. The consumer (or their\n"
                + "framework) still owns pool creation and tuning.\n"
                + "@param defaultSource source for untenanted SQL; must not be {@code null}\n"
                + "@param sourcesByTenant per-tenant sources keyed by divined tenant value; may be empty\n"
                + (multiTenant
                    ? "@param fanOutExecutor the executor scatter workers run on; the supplier owns its\n"
                        + "concurrency bound (e.g. virtual threads); must not be {@code null}\n"
                        + "@param fanOutTimeout the per-scatter deadline, enforced by the join whichever\n"
                        + "executor runs the workers; must be positive\n"
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
                    ? "this(defaultDataSource, dataSourcesByTenant, dialect, DEFAULT_FAN_OUT_CONCURRENCY,"
                        + " DEFAULT_FAN_OUT_TIMEOUT)"
                    : "this(new $T(defaultDataSource, dialect, null), wrapSources(dataSourcesByTenant, dialect))",
                source)
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
        var acquire = acquireBuilder
            .addException(SQL_EXCEPTION)
            .addStatement("return $T.acquire(defaultSource.dataSource(), defaultSource.dialect(),"
                + " defaultSource.settings(), abortExecutor$L)", pinnedConnection, payloadArgs.toString())
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
                + " tenantSource.settings(), abortExecutor$L)", pinnedConnection, payloadArgs.toString())
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
                + "code, never a held value.\n");
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
        builder.addMethod(canonicalConstructor);
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
     * so the provider's {@code depth} is the only nesting counter on that connection, and the
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
                                              boolean multiTenant, SessionHooks sessionHooks) {
        var payload = payloadParams(sessionHooks);
        boolean handled = sessionHooks instanceof SessionHooks.Handled;
        var optionalKey = ParameterizedTypeName.get(ClassName.get("java.util", "Optional"), tenantKey);
        var entryType = self.nestedClass("Entry");
        var entryMapType = ParameterizedTypeName.get(MAP, optionalKey, entryType);

        var runtimeField = FieldSpec.builder(runtime, "runtime", Modifier.PRIVATE, Modifier.FINAL).build();
        var policyField = FieldSpec.builder(commitPolicy, "commitPolicy", Modifier.PRIVATE, Modifier.FINAL).build();
        var entriesField = FieldSpec.builder(entryMapType, "entries", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("new $T<>()", CONCURRENT_HASH_MAP)
            .addJavadoc("Concurrent in both topologies, with per-key single acquisition\n"
                + "({@code computeIfAbsent}, the only minting mechanism in the carrier): under fan-out,\n"
                + "scatter partitions distinct keys one worker each but nothing structural prevents a\n"
                + "worker and the dispatch thread racing the same key, so one-pin-per-key is this map's\n"
                + "contract, not an accident of the callers; under lazy single-tenant acquisition,\n"
                + "serial dispatch is an execution-strategy assumption rather than a structural\n"
                + "guarantee, and the concurrent map is cheaper than defending it.\n")
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

        var constructorBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(runtime, "runtime")
            .addParameter(commitPolicy, "commitPolicy");
        for (var p : payload) {
            constructorBuilder.addParameter(p.javaType(), p.name());
        }
        constructorBuilder
            .addStatement("this.runtime = runtime")
            .addStatement("this.commitPolicy = commitPolicy");
        for (var p : payload) {
            constructorBuilder.addStatement("this.$L = $L", p.name(), p.name());
        }
        var constructor = constructorBuilder
            .addJavadoc((multiTenant
                ? "Builds a per-operation carrier over {@code runtime} for one request. One instance per\n"
                    + "operation. Concurrency is confined to {@link #scatter}'s bounded workers, each owning\n"
                    + "one keyed connection single-threaded through {@link #dslFor}, with the dispatch thread\n"
                    + "blocked on the join for the scatter's whole duration; every other access runs serially\n"
                    + "on the dispatch thread.\n"
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
                .beginControlFlow("if (key.isPresent() && timedOutTenants.contains(key.get()))")
                .addComment("The key's scatter worker missed the join deadline; its connection may still be")
                .addComment("executing, so it is never reused within the operation.")
                .addStatement("throw new $T($S + key.get() + $S)", IllegalStateException.class,
                    "Tenant '", "' timed out earlier in this operation; its connection is never reused.")
                .endControlFlow();
        }
        entryForBuilder
            .addStatement("$T entry", entryType)
            .beginControlFlow("try")
            .addComment("Per-key single acquisition: exactly one pin per key, the map's one minting")
            .addComment("mechanism. The checked acquisition failure tunnels out of the compute lambda")
            .addComment("unchanged. The whole entry is minted here: pin and mount, bind a DSLContext to the")
            .addComment("pinned connection through the resolved source's dialect and settings, swap in one")
            .addComment("transaction provider (one per pinned connection, so its depth is the only nesting")
            .addComment("counter on that connection). jOOQ's single-connection provider treats release as a")
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
            .addCode("});\n")
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
                + "{@code Optional.empty()} is the default source, a present value routes per tenant.\n")
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
                + "sibling of {@link #dslFor}; the single-tenant path resolves every context through\n"
                + "this, as the one-key case.\n")
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
            carrier.addField(scatterWorkerMarkerField);
        }
        carrier.addField(runtimeField)
            .addField(policyField);
        for (var p : payload) {
            carrier.addField(FieldSpec.builder(p.javaType(), p.name(), Modifier.PRIVATE, Modifier.FINAL)
                .addJavadoc("Mount payload, held for the life of the request; any fetcher may trigger the\n"
                    + "first mount for a key.\n")
                .build());
        }
        carrier.addField(entriesField);
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
            .addMethod(staticDslDefault(self));
        carrier.addType(entryClass);
        if (multiTenant) {
            carrier.addField(FieldSpec.builder(String.class, FAN_OUT_TENANTS_KEY_FIELD,
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", FAN_OUT_TENANTS_KEY_VALUE)
                .addJavadoc("The {@code graphQLContext} key the request's fan-out tenant collection is\n"
                    + "published under. Written by the generated factory's dedicated tenant-collection\n"
                    + "parameter; read here by {@link #fanOutDomain}. One constant so the two sites cannot\n"
                    + "drift, and a graphitron-owned name no contextArgument can collide with.\n")
                .build());
            carrier.addField(FieldSpec.builder(SLF4J_LOGGER, "LOGGER",
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, self)
                .build());
            carrier.addMethod(scatterMethod(self, tenantKey))
                .addMethod(scatterWorkerMethod(self, tenantKey))
                .addMethod(fanOutDomain(self, tenantKey))
                .addMethod(fanOutRows(self, tenantKey))
                .addMethod(fanOutBatchRows(self, tenantKey))
                .addMethod(collapseFanOut(self))
                .addMethod(logFanOutFailure(self))
                .addMethod(staticDslFor(self, tenantKey))
                .addMethod(divinedTenant(tenantKey))
                .addMethod(divinedTenantAgree())
                .addMethod(tenantSlot())
                .addMethod(loaderName())
                .addMethod(tenantLoaderName())
                .addType(outcomeType(self, tenantKey))
                .addType(fanOutFailureType());
        }
        return carrier.build();
    }

    /**
     * {@code static List<K> fanOutDomain(DataFetchingEnvironment env)}: the request's fan-out
     * domain, the intersection of the configured tenant map's keys and the factory-supplied
     * tenant collection, with the two directions of the difference treated differently: a hosted
     * tenant the request did not name is silently never queried (the authorization pre-filter),
     * while a named tenant the deployment does not host is a request-level error before any SQL
     * runs (the derived tenant set is the statement that data could exist there; skipping would
     * return incomplete results presented as complete). Iteration order is the tenant map's
     * configured key order filtered by the request set, so the union's concatenation order is
     * deployment-stable and the request collection's own iteration order is never load-bearing.
     */
    private static MethodSpec fanOutDomain(ClassName self, TypeName tenantKey) {
        return MethodSpec.methodBuilder("fanOutDomain")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(LIST, tenantKey))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("$T<$T> requested = env.getGraphQlContext().get($L)",
                COLLECTION, tenantKey, FAN_OUT_TENANTS_KEY_FIELD)
            .beginControlFlow("if (requested == null)")
            .addStatement("throw new $T($S)", IllegalStateException.class,
                "No fan-out tenant collection in the GraphQL context: a schema with @tenantFanOut"
                    + " fields adds a dedicated tenant-collection parameter to the generated"
                    + " factories (newExecutionInput / newOwnedExecutionInput); build the request"
                    + " through one of them.")
            .endControlFlow()
            .addStatement("$T<$T> hosted = of(env).runtime.tenantKeys()", SET, tenantKey)
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
                + "order, filtered by the factory-supplied tenant collection. A hosted tenant the\n"
                + "request did not name is never queried; a named tenant the deployment does not host\n"
                + "is a request-level error before any SQL runs.\n"
                + "@param env the fanned field's {@code DataFetchingEnvironment}\n")
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
            .addStatement("$T<$T<$T>> outcomes = of(env).scatter(fanOutDomain(env), perTenant)",
                LIST, outcome, listOfR)
            .addStatement("$T<Object> elements = new $T<>()", LIST, ARRAY_LIST)
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfR)
            .beginControlFlow("if (outcome instanceof Outcome.Success<$T> success)", listOfR)
            .beginControlFlow("for ($T row : success.value())", r)
            .addStatement("elements.add($T.newResult().data(row).localContext(success.key()).build())",
                DATA_FETCHER_RESULT)
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addComment("Failed and timed-out tenants append after the successful rows (we cannot know how")
            .addComment("many rows they would have returned), one marker each.")
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfR)
            .beginControlFlow("if (!(outcome instanceof Outcome.Success))")
            .addStatement("elements.add(logFanOutFailure(outcome))")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return elements")
            .addJavadoc("Runs the fanned field's statement once per domain tenant and unions the outcomes\n"
                + "in domain order: each row wrapped as a per-element {@code DataFetcherResult} whose\n"
                + "{@code localContext} carries the row's tenant, each failed or timed-out tenant as one\n"
                + "{@code FanOutFailure} marker appended after the successful rows. Feed the result to\n"
                + "{@link #collapseFanOut}.\n")
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
            .addStatement("$T<$T<$T>> outcomes = of(env).scatter(fanOutDomain(env), perTenant)",
                LIST, outcome, listOfListOfR)
            .addStatement("$T<$T> merged = new $T<>(keyCount)", LIST, listOfObject, ARRAY_LIST)
            .beginControlFlow("for (int i = 0; i < keyCount; i++)")
            .addStatement("merged.add(new $T<>())", ARRAY_LIST)
            .endControlFlow()
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfListOfR)
            .beginControlFlow("if (outcome instanceof Outcome.Success<$T> success)", listOfListOfR)
            .beginControlFlow("for (int i = 0; i < keyCount; i++)")
            .beginControlFlow("for ($T row : success.value().get(i))", r)
            .addStatement("merged.get(i).add($T.newResult().data(row).localContext(success.key()).build())",
                DATA_FETCHER_RESULT)
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfListOfR)
            .beginControlFlow("if (!(outcome instanceof Outcome.Success))")
            .addStatement("$T failure = logFanOutFailure(outcome)", self.nestedClass("FanOutFailure"))
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
                + "@param keyCount the parent batch size; {@code perTenant} must return one group per key\n")
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

    /** Logs one failure with a fresh correlation id and returns its in-band marker. */
    private static MethodSpec logFanOutFailure(ClassName self) {
        var outcome = self.nestedClass("Outcome");
        var failureType = self.nestedClass("FanOutFailure");
        return MethodSpec.methodBuilder("logFanOutFailure")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(failureType)
            .addParameter(ParameterizedTypeName.get(outcome, WildcardTypeName.subtypeOf(Object.class)), "outcome")
            .addStatement("$T correlationId = $T.randomUUID()", UUID_CLASS, UUID_CLASS)
            .beginControlFlow("if (outcome instanceof Outcome.Failed<?> failed)")
            .addStatement("LOGGER.error($S, failed.key(), correlationId, failed.cause())",
                "Tenant fan-out failed for tenant {}; correlation id = {}")
            .addStatement("return new FanOutFailure(correlationId.toString(), FanOutFailure.FAILED)")
            .endControlFlow()
            .beginControlFlow("if (outcome instanceof Outcome.TimedOut)")
            .addStatement("LOGGER.error($S, outcome.key(), correlationId)",
                "Tenant fan-out timed out for tenant {}; correlation id = {}")
            .addStatement("return new FanOutFailure(correlationId.toString(), FanOutFailure.TIMED_OUT)")
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
            .addStatement("return loaderName(env) + $S + env.getLocalContext()", " tenant:")
            .addJavadoc("The tenant-partitioned DataLoader name for a field inheriting a divined tenant:\n"
                + "{@link #loaderName} plus an opaque tenant segment (never parsed back), so each\n"
                + "loader batch stays tenant-homogeneous and its captured environment routes the\n"
                + "right source. The separator contains characters no GraphQL path segment can,\n"
                + "so the segment cannot collide with a path suffix.\n")
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
     * {@code static T divinedTenant(Object... candidates)}: folds every runtime value of a field's
     * tenant bindings into the one divined key. Collections flatten (a list-valued binding
     * contributes each element); all non-null values must agree; an all-null/absent fold is a
     * request-level error before any SQL, as is a value of the wrong Java type.
     */
    private static MethodSpec divinedTenant(TypeName tenantKey) {
        var b = MethodSpec.methodBuilder("divinedTenant")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(Object[].class, "candidates")
            .varargs()
            .addStatement("Object key = null")
            .beginControlFlow("for (Object candidate : candidates)")
            .addStatement("key = agreeOnTenant(key, candidate)")
            .endControlFlow()
            .beginControlFlow("if (key == null)")
            .addComment("Absent binding value: a request-level error before any SQL, same family as the")
            .addComment("unknown-tenant acquisition failure.")
            .addStatement("throw new $T($S)", NO_SUCH_ELEMENT,
                "The tenant binding value is absent; cannot route the operation to a tenant database.")
            .endControlFlow()
            .beginControlFlow("if (key instanceof $T typed)", tenantKey)
            .addStatement("return typed")
            .endControlFlow();
        // Wire-form coercion for numeric tenant columns: decoded node-id segments and ID-typed
        // arguments arrive as Strings; parse them to the catalog type so per-row keys and typed
        // keys agree in one map. Non-numeric parses fail loudly (NumberFormatException).
        if (tenantKey.equals(ClassName.get(Integer.class)) || tenantKey.equals(ClassName.get(Long.class))) {
            b.beginControlFlow("if (key instanceof String s)")
                .addStatement("return $T.valueOf(s)", tenantKey)
                .endControlFlow();
        }
        return b.addStatement("throw new $T($S + key + $S)", IllegalArgumentException.class,
                "Divined tenant value '", "' does not have the tenant column's Java type.")
            .addJavadoc("Folds the runtime values of a field's tenant bindings into the one divined key:\n"
                + "collections flatten, all non-null values must agree, and an absent or wrongly-typed\n"
                + "value is a request-level error before any SQL. For a numeric tenant column a\n"
                + "String candidate parses to the column type (decoded node-id segments arrive in\n"
                + "wire form).\n"
                + "@param candidates each bound slot's runtime value (a value, a collection of values, or {@code null})\n")
            .build();
    }

    /** The recursive agree-fold behind {@code divinedTenant}: flattens collections, rejects disagreement. */
    private static MethodSpec divinedTenantAgree() {
        var collection = ClassName.get("java.util", "Collection");
        return MethodSpec.methodBuilder("agreeOnTenant")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(Object.class, "current")
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
            .beginControlFlow("if (current != null && !current.equals(candidate))")
            .addStatement("throw new $T($S + current + $S + candidate)", IllegalArgumentException.class,
                "Tenant bindings disagree within one operation: '", "' vs '")
            .endControlFlow()
            .addStatement("return candidate")
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
     * Emits the generated hook class: one final class with static {@code mount} and
     * {@code unmount} methods that call the consumer's resolved methods directly (nothing is
     * registered, nothing is dispatched polymorphically; the call site is a direct static
     * invocation). Called only when {@link SessionHooks#emitsHookImplementation()} holds, the
     * single membership fact the emit plan also reads. This class is where the provider-free
     * {@code Configuration} is built, from the connection and the resolved source's dialect and
     * settings (so a consumer's schema mapping reaches their own mount method, and the
     * transaction-demarcation provider structurally cannot), and where the payload is spread into
     * the mount method's own declaration order; {@code PinnedConnection} stays free of both.
     */
    private static TypeSpec sessionHookImpl(SessionHooks sessionHooks) {
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
     * reflection), payload parameters read the generated method's same-named locals, and the
     * unmount's handle parameter reads {@code handle}.
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
                default -> throw new IllegalStateException(
                    "unexpected hook parameter source " + p.source().getClass().getSimpleName()
                        + " on " + ref.className() + "#" + ref.methodName());
            }
        }
        return args.build();
    }
}
