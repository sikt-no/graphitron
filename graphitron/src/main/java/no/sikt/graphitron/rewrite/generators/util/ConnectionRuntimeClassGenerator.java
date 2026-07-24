package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.FunctionHooks;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.None;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.Unmount;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.Variables;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Emits the connection-lifecycle runtime substrate into the consumer's
 * {@code <outputPackage>.schema} package: an application-scoped {@code GraphitronRuntime} that owns
 * the {@code DataSource}, the acquisition-scoped {@code PinnedConnection} that mounts and unmounts
 * per-request identity around exactly one pinned connection, and the {@code SessionHook} seam the
 * database-side connect/disconnect callables plug into.
 *
 * <p>Emitted (not shipped as a graphitron artifact) so it follows the {@code GraphitronContext}
 * precedent: the bodies depend only on the JDK ({@code javax.sql.DataSource},
 * {@code java.sql.Connection}, {@code java.util.concurrent.Executor}) and jOOQ
 * ({@code org.jooq.SQLDialect}); no auth-framework type and no graphitron type ever appears. In a
 * multi-tenant build the {@code TenantConnections} carrier additionally references graphql-java's
 * {@code DataFetchingEnvironment} (its routing statics resolve the carrier off the GraphQL
 * context), which every generated consumer already has on the classpath. The bodies must be valid
 * Java 17 (verified by the {@code graphitron-sakila-example} {@code <release>17</release>} compile).
 *
 * <h2>The two lifecycles</h2>
 * Connection setup is application-scoped ({@code GraphitronRuntime}, built once at wiring time via
 * {@code Graphitron.runtime(dataSource, dialect)}); identity is acquisition-scoped
 * ({@code PinnedConnection}, one per operation). {@code PinnedConnection} carries <em>no</em>
 * transaction concept: the commit-policy axis (commit-vs-rollback) is the orthogonal transaction
 * concern that the {@code TransactionProvider} and execution instrumentation layer over this
 * seam. The connect OUT value is the only thing called a "handle" here.
 *
 * <h2>The lifecycle contract (unit-pinned in {@code ConnectionRuntimeClassGeneratorTest})</h2>
 * <ul>
 *   <li><b>Acquire.</b> {@code DataSource.getConnection()}, then {@code setAutoCommit(true)}, then
 *       the connect hook with the opaque claims, capturing its OUT handle. <b>Fail closed:</b> if
 *       connect throws (it may have partially mounted session state first), the connection is
 *       evicted, never returned, and the failure propagates before any operation SQL runs.</li>
 *   <li><b>Release.</b> Any transaction the operation left open is rolled back and autocommit
 *       restored, then the disconnect hook fires on <em>every</em> completion path (success, error,
 *       cancellation) bound to the captured handle; release is idempotent. <b>Evict on unmount
 *       failure:</b> if disconnect throws or cannot run, the physical connection is aborted and
 *       never returned to the pool, so a connection whose identity cannot be proven unmounted gets
 *       no next borrower.</li>
 * </ul>
 * Eviction uses {@link java.sql.Connection#abort(java.util.concurrent.Executor)} (JDBC, valid Java
 * 17): pool wrappers (Agroal/HikariCP) honour it as a true physical evict where {@code close()}
 * merely reclaims the connection to the pool. The runtime supplies a same-thread executor.
 *
 * <h3>Hooks run outside any transaction (structural guarantee)</h3>
 * Session identity is connection-scoped state, never transactional state: neither mount nor unmount
 * may depend on any transaction's outcome. Both lifecycle ends enforce this rather than assume it.
 * Acquire normalizes autocommit before connect, so a pool configured autocommit=false (Agroal and
 * Hikari both support this) cannot put the mount inside an implicit never-committed transaction; on
 * Postgres {@code set_config}/{@code SET} are transactional, so an in-transaction mount would revert
 * with a mutation field's rollback and an in-transaction clear would be reverted by the pool's
 * return-rollback, leaving identity alive for the next borrower. Release settles any transaction the
 * operation left open before disconnect, so the clears commit immediately. This is what makes the
 * {@code <variables>} sugar's mounts and clears take effect regardless of pool configuration; the
 * matching contract for consumer-authored function hooks (session-scoped state only, no reliance on
 * a surrounding transaction) is documented on the {@code SessionHook} javadocs and in
 * {@code runtime-extension-points.adoc}.
 *
 * <h3>The settle re-fire for unconfirmed function hooks</h3>
 * Documentation alone is not trusted for the one thing graphitron cannot verify: whether a
 * consumer-authored connect hook's mounted state actually survives a transaction commit or rollback
 * (identity parked in an {@code ON COMMIT DELETE ROWS} temp table would not). A paired function hook
 * declares survival with {@code <stateSurvivesTransactions>true</stateSurvivesTransactions>};
 * acquisition-scoped mounting then suffices. Unconfirmed (the default), the runtime bakes
 * {@code remountAfterSettle=true} into acquisition, and {@code PinnedConnection#afterSettle}, wired
 * as the transaction provider's settle-completion callback, re-fires the pair (disconnect the old
 * handle, connect a fresh one) after each top-level mutation-field settle, in autocommit, so
 * post-commit read-back projections and later mutation fields always see mounted identity. Queries
 * never open a transaction, so the safe default costs nothing on the read path. The
 * {@code <variables>} sugar opts in structurally (autocommit mounts of session-scoped GUCs survive
 * settles), and the unmount-free opt-out has no pair to re-fire, so neither ever remounts. A remount
 * failure evicts immediately: later serial mutation fields must fail on a dead connection rather
 * than run with unproven identity.
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
 * <h2>Session hooks (slice 3)</h2>
 * With no {@code <sessionState>} configured the runtime bakes {@code SessionHook.NONE} (a no-op
 * null-object: mounts and unmounts nothing) so the path never branches on a nullable hook. A
 * configured {@link SessionStateConfig} additionally emits a concrete
 * {@value #SESSION_HOOK_IMPL_CLASS_NAME} the runtime constructor bakes in place of {@code NONE}:
 * <ul>
 *   <li><b>Function-hook form</b> ({@link FunctionHooks}) calls consumer-authored database callables via
 *       JDBC {@link java.sql.CallableStatement}; a declared OUT handle is captured by connect and bound
 *       by disconnect. An {@link Unmount.UnmountFree} disconnect is the explicit unmount-free opt-out
 *       (an empty disconnect body).</li>
 *   <li><b>Postgres {@code <variables>} sugar</b> ({@link Variables}) emits both hook halves from one
 *       resolved variable set: connect issues a single-round-trip {@code set_config(...)} per variable
 *       reading the claim from the payload JSON, disconnect clears exactly those variables (to the empty
 *       string). Both halves come from the same carrier, so "disconnect clears exactly what connect set"
 *       is structural. This form additionally guards its dialect: the runtime constructor fails closed
 *       when built with a non-Postgres dialect.</li>
 * </ul>
 * The emitter forks on an exhaustive {@code switch} over the sealed {@link SessionStateConfig}, so a
 * fourth form becomes a compile error at this seam.
 *
 * <h3>Postgres GUC clear semantics (why {@code set_config(name, '', false)}, not {@code RESET})</h3>
 * A never-set custom GUC reads back {@code NULL}, but once a placeholder GUC has been set in a session
 * both {@code RESET} and {@code set_config(name, NULL, false)} leave it as the empty string, not
 * {@code NULL} (Postgres cannot restore a touched placeholder GUC to unset). {@code RESET} therefore
 * buys no fail-closed advantage here, so disconnect uses {@code set_config(name, '', false)}: it is
 * symmetric with connect (same mechanism, one carrier) and deterministic. The fail-closed guarantee
 * lives in the documented RLS-policy pattern, which must treat {@code NULL} and the empty string
 * identically as "no identity".
 */
public final class ConnectionRuntimeClassGenerator {

    public static final String RUNTIME_CLASS_NAME = "GraphitronRuntime";
    public static final String PINNED_CONNECTION_CLASS_NAME = "PinnedConnection";
    public static final String SESSION_HOOK_CLASS_NAME = "SessionHook";
    /** The concrete {@code SessionHook} emitted from a configured {@code <sessionState>} block (slice 3). */
    public static final String SESSION_HOOK_IMPL_CLASS_NAME = "GraphitronSessionHook";
    /** The per-operation tenant-keyed connection carrier (slice 4). */
    public static final String TENANT_CONNECTIONS_CLASS_NAME = "TenantConnections";

    private static final ClassName CONNECTION = ClassName.get("java.sql", "Connection");
    private static final ClassName SQL_EXCEPTION = ClassName.get("java.sql", "SQLException");
    private static final ClassName CALLABLE_STATEMENT = ClassName.get("java.sql", "CallableStatement");
    private static final ClassName PREPARED_STATEMENT = ClassName.get("java.sql", "PreparedStatement");
    private static final ClassName JDBC_TYPES = ClassName.get("java.sql", "Types");
    private static final ClassName DATA_SOURCE = ClassName.get("javax.sql", "DataSource");
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

    private ConnectionRuntimeClassGenerator() {}

    /**
     * @param outputPackage the consumer's root output package; the classes are emitted into
     *                      {@code outputPackage + ".schema"} (beside {@code GraphitronContext})
     * @param sessionState  the resolved {@code <sessionState>} config: {@link None} keeps
     *                      {@code SessionHook.NONE}; {@link FunctionHooks}/{@link Variables} additionally
     *                      emit a concrete {@link #SESSION_HOOK_IMPL_CLASS_NAME} the runtime bakes in
     */
    public static List<TypeSpec> generate(String outputPackage, SessionStateConfig sessionState) {
        return generate(outputPackage, sessionState, null);
    }

    /**
     * Canonical form carrying the divined tenant key type. {@code tenantKeyType} is the tenant
     * Java type read off the jOOQ catalog's tenant column when {@code <tenantColumn>} is
     * configured, or {@code null} for single-tenant builds. A configured type replaces the
     * erased {@code Object} on every tenant-keyed surface (the constructor map, the keyed
     * acquisition, the per-operation carrier), so a consumer wiring a map keyed with the wrong
     * type is a compile error rather than a first-request lookup miss.
     */
    public static List<TypeSpec> generate(String outputPackage, SessionStateConfig sessionState,
                                          TypeName tenantKeyType) {
        TypeName tenantKey = tenantKeyType == null
            ? OBJECT_KEY
            : (tenantKeyType.isPrimitive() ? tenantKeyType.box() : tenantKeyType);
        String schemaPackage = outputPackage + ".schema";
        var sessionHook = ClassName.get(schemaPackage, SESSION_HOOK_CLASS_NAME);
        var sessionHookImpl = ClassName.get(schemaPackage, SESSION_HOOK_IMPL_CLASS_NAME);
        var pinnedConnection = ClassName.get(schemaPackage, PINNED_CONNECTION_CLASS_NAME);
        var runtime = ClassName.get(schemaPackage, RUNTIME_CLASS_NAME);
        var tenantConnections = ClassName.get(schemaPackage, TENANT_CONNECTIONS_CLASS_NAME);
        var instrumentation = ClassName.get(schemaPackage, GraphitronConnectionInstrumentationGenerator.CLASS_NAME);
        var provider = ClassName.get(schemaPackage, GraphitronTransactionProviderGenerator.CLASS_NAME);
        var commitPolicy = provider.nestedClass(GraphitronTransactionProviderGenerator.COMMIT_POLICY_ENUM_NAME);

        RuntimeHookProjection projection = projectHookFacts(sessionState, sessionHook, sessionHookImpl);
        boolean multiTenant = tenantKeyType != null;

        var units = new ArrayList<TypeSpec>();
        units.add(sessionHook(sessionHook));
        units.add(pinnedConnection(pinnedConnection, sessionHook, multiTenant));
        units.add(runtime(sessionHook, pinnedConnection, instrumentation, projection, tenantKey, multiTenant));
        units.add(tenantConnections(tenantConnections, runtime, pinnedConnection, provider, commitPolicy, tenantKey,
            multiTenant));
        TypeSpec impl = sessionHookImpl(sessionHook, sessionState);
        if (impl != null) {
            units.add(impl);
        }
        return List.copyOf(units);
    }

    /** Back-compatible overload for callers that mount no identity (unit-tier drivers, no {@code <sessionState>}). */
    public static List<TypeSpec> generate(String outputPackage) {
        return generate(outputPackage, SessionStateConfig.none());
    }

    /**
     * The three facts the runtime emission derives from the {@code <sessionState>} config: which hook
     * the constructor bakes, whether construction guards for a Postgres dialect (the {@code <variables>}
     * sugar bakes {@code set_config} statements), and whether acquired connections re-fire the hook pair
     * after each top-level transaction settle (unconfirmed {@link Unmount.PairedDisconnect} only; the
     * sugar survives settles structurally and {@link Unmount.UnmountFree} has no pair to re-fire).
     */
    private record RuntimeHookProjection(CodeBlock hookInitializer, boolean requiresPostgres, boolean remountAfterSettle) {}

    /**
     * Projects the sealed config into the {@link RuntimeHookProjection} in one exhaustive {@code switch},
     * so a fourth {@link SessionStateConfig} form must decide all three facts here (a compile error, not
     * three independent silent defaults).
     */
    private static RuntimeHookProjection projectHookFacts(SessionStateConfig config, ClassName sessionHook, ClassName sessionHookImpl) {
        var baked = CodeBlock.of("new $T()", sessionHookImpl);
        return switch (config) {
            case None ignored -> new RuntimeHookProjection(CodeBlock.of("$T.NONE", sessionHook), false, false);
            case FunctionHooks fh -> new RuntimeHookProjection(baked, false,
                fh.unmount() instanceof Unmount.PairedDisconnect pd && !pd.survivesTransactions());
            // The <variables> sugar requires a Postgres dialect at construction (fail loud at wiring
            // time, not at the first request) and opts in to survival structurally: its set_config
            // mounts run in autocommit and session-scoped GUCs survive settles.
            case Variables ignored -> new RuntimeHookProjection(baked, true, false);
        };
    }

    /** The connect/disconnect seam. Open interface: slice 1 fakes it, slice 3 generates the concrete impl. */
    private static TypeSpec sessionHook(ClassName sessionHook) {
        var connect = MethodSpec.methodBuilder("connect")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(String.class)
            .addParameter(CONNECTION, "connection")
            .addParameter(String.class, "claims")
            .addException(SQL_EXCEPTION)
            .addJavadoc("Mounts the caller's identity onto the freshly pinned {@code connection} from the\n"
                + "opaque {@code claims} payload, returning an optional opaque handle (e.g. a RAS session\n"
                + "id) that {@link #disconnect} is later called with. Runs before any operation SQL. May\n"
                + "throw to reject the request (missing claim, unknown person, unentitled role); the\n"
                + "runtime then fails closed.\n"
                + "\n"
                + "<p><b>State contract:</b> must set <em>session-scoped</em> connection state only\n"
                + "(Postgres {@code set_config(key, value, false)}, never {@code SET LOCAL}; Oracle RAS\n"
                + "attach is a session operation and complies), and must not rely on a surrounding\n"
                + "transaction committing or rolling back. May assume it is invoked outside any open\n"
                + "transaction: the runtime normalizes the connection to autocommit before this call.\n"
                + "@param connection the pinned connection, normalized to autocommit, before any operation SQL\n"
                + "@param claims the opaque, unvalidated claims payload (never parsed by graphitron)\n"
                + "@return an opaque handle to thread to {@link #disconnect}, or {@code null} if none\n")
            .build();

        var disconnect = MethodSpec.methodBuilder("disconnect")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(void.class)
            .addParameter(CONNECTION, "connection")
            .addParameter(String.class, "handle")
            .addException(SQL_EXCEPTION)
            .addJavadoc("Unmounts the identity {@link #connect} mounted, bound to the {@code handle} connect\n"
                + "returned (or {@code null}). Fires on every release path. If this throws or cannot run,\n"
                + "the runtime evicts the physical connection rather than returning tainted state to the pool.\n"
                + "\n"
                + "<p><b>State contract:</b> must reset <em>session-scoped</em> connection state only, and\n"
                + "must not rely on a surrounding transaction committing or rolling back. May assume it is\n"
                + "invoked outside any open transaction: the runtime settles any transaction the operation\n"
                + "left open (rollback, restore autocommit) before this call, so the clears take effect\n"
                + "immediately.\n"
                + "@param connection the still-pinned connection, outside any open transaction\n"
                + "@param handle the opaque handle {@link #connect} returned, or {@code null}\n")
            .build();

        // No-op null-object for the no-<sessionState> path: mounts and unmounts nothing, so acquire and
        // release never branch on a nullable hook. Slice 6's generation-time warning describes this default.
        var none = FieldSpec.builder(sessionHook, "NONE", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$L", TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(sessionHook)
                .addMethod(MethodSpec.methodBuilder("connect")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(String.class)
                    .addParameter(CONNECTION, "connection")
                    .addParameter(String.class, "claims")
                    .addStatement("return null")
                    .build())
                .addMethod(MethodSpec.methodBuilder("disconnect")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(void.class)
                    .addParameter(CONNECTION, "connection")
                    .addParameter(String.class, "handle")
                    .build())
                .build())
            .addJavadoc("No-op hook for the no-{@code <sessionState>} path: mounts and unmounts nothing.\n")
            .build();

        return TypeSpec.interfaceBuilder(SESSION_HOOK_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("The consumer-owned database session-identity seam: a connect callable mounts\n"
                + "per-request identity on the pinned connection from the opaque claims payload and returns\n"
                + "an optional handle; a paired disconnect callable unmounts it. Both are the database's own\n"
                + "language (RAS/VPD on Oracle, {@code set_config} on Postgres); graphitron guarantees the\n"
                + "pair runs at mount and unmount, outside any open transaction. Identity is connection-scoped\n"
                + "state, never transactional state: hooks set and reset session-scoped state and never depend\n"
                + "on a transaction's outcome (see the state contract on each method). Slice 3 generates the\n"
                + "concrete implementation from {@code <sessionState>} configuration; {@link #NONE} is the\n"
                + "no-op default.\n")
            .addMethod(connect)
            .addMethod(disconnect)
            .addField(none)
            .build();
    }

    /**
     * The acquisition-scoped pinned connection: acquire/release lifecycle with fail-closed + evict.
     * Multi-tenant builds additionally emit {@code abort()}, the straggler seam the scatter helper's
     * timeout path routes through: evict without the disconnect hook, safe against a worker that may
     * still be executing on the connection.
     */
    private static TypeSpec pinnedConnection(ClassName pinnedConnection, ClassName sessionHook, boolean multiTenant) {
        var connectionField = FieldSpec.builder(CONNECTION, "connection", Modifier.PRIVATE, Modifier.FINAL).build();
        var hookField = FieldSpec.builder(sessionHook, "hook", Modifier.PRIVATE, Modifier.FINAL).build();
        var claimsField = FieldSpec.builder(String.class, "claims", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("Retained for the settle re-fire: an unconfirmed hook's remount re-runs connect\n"
                + "with the same opaque payload.\n")
            .build();
        var handleField = FieldSpec.builder(String.class, "handle", Modifier.PRIVATE)
            .addJavadoc("Mutable: each settle re-fire disconnects the old handle and captures a fresh one.\n")
            .build();
        var remountAfterSettleField = FieldSpec.builder(boolean.class, "remountAfterSettle", Modifier.PRIVATE, Modifier.FINAL).build();
        var abortExecutorField = FieldSpec.builder(EXECUTOR, "abortExecutor", Modifier.PRIVATE, Modifier.FINAL).build();
        var releasedField = FieldSpec.builder(boolean.class, "released", Modifier.PRIVATE).build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .addParameter(CONNECTION, "connection")
            .addParameter(sessionHook, "hook")
            .addParameter(String.class, "claims")
            .addParameter(String.class, "handle")
            .addParameter(boolean.class, "remountAfterSettle")
            .addParameter(EXECUTOR, "abortExecutor")
            .addStatement("this.connection = connection")
            .addStatement("this.hook = hook")
            .addStatement("this.claims = claims")
            .addStatement("this.handle = handle")
            .addStatement("this.remountAfterSettle = remountAfterSettle")
            .addStatement("this.abortExecutor = abortExecutor")
            .build();

        var acquire = MethodSpec.methodBuilder("acquire")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(pinnedConnection)
            .addParameter(DATA_SOURCE, "dataSource")
            .addParameter(sessionHook, "hook")
            .addParameter(String.class, "claims")
            .addParameter(EXECUTOR, "abortExecutor")
            .addParameter(boolean.class, "remountAfterSettle")
            .addException(SQL_EXCEPTION)
            .addStatement("$T connection = dataSource.getConnection()", CONNECTION)
            .addStatement("String handle")
            .beginControlFlow("try")
            .addComment("Hooks run outside any transaction, structurally: normalize autocommit so a pool")
            .addComment("configured autocommit=false cannot put the mount inside an implicit never-committed")
            .addComment("transaction (on Postgres, set_config/SET revert with a rolled-back transaction).")
            .addStatement("connection.setAutoCommit(true)")
            .addStatement("handle = hook.connect(connection, claims)")
            .nextControlFlow("catch ($T connectFailure)", Throwable.class)
            .addComment("Fail closed: connect may have partially mounted session state before throwing.")
            .addComment("Evict rather than return a half-mounted connection to the pool; reject before any SQL.")
            .addStatement("evict(connection, abortExecutor)")
            .addStatement("throw rethrow(connectFailure)")
            .endControlFlow()
            .addStatement("return new $T(connection, hook, claims, handle, remountAfterSettle, abortExecutor)", pinnedConnection)
            .addJavadoc("Pins one connection, normalizes it to autocommit, and mounts identity on it. The\n"
                + "connect hook therefore always runs outside any transaction, whatever the pool's\n"
                + "autocommit configuration. Fail-closed: a throwing connect hook (or a failed\n"
                + "normalization) evicts the connection and propagates before any operation SQL runs.\n"
                + "{@code remountAfterSettle} is baked by the runtime from the {@code <sessionState>}\n"
                + "config: true only for a function-hook pair without the declared survival opt-in.\n")
            .build();

        var connectionAccessor = MethodSpec.methodBuilder("connection")
            .addModifiers(Modifier.PUBLIC)
            .returns(CONNECTION)
            .addStatement("return connection")
            .addJavadoc("The pinned connection every fetch of this operation runs on.\n")
            .build();

        var release = MethodSpec.methodBuilder("release")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .beginControlFlow("if (released)")
            .addStatement("return")
            .endControlFlow()
            .addStatement("released = true")
            .beginControlFlow("try")
            .addComment("Hooks run outside any transaction, structurally: if the operation left a transaction")
            .addComment("open (e.g. it died mid-mutation before the provider settled), roll it back and restore")
            .addComment("autocommit first, so the disconnect's clears take effect immediately rather than")
            .addComment("sitting in an uncommitted transaction the pool's return-rollback would revert.")
            .beginControlFlow("if (!connection.getAutoCommit())")
            .addStatement("connection.rollback()")
            .addStatement("connection.setAutoCommit(true)")
            .endControlFlow()
            .addStatement("hook.disconnect(connection, handle)")
            .nextControlFlow("catch ($T disconnectFailure)", Throwable.class)
            .addComment("Identity cannot be proven unmounted: evict the physical connection, never return it.")
            .addStatement("evict(connection, abortExecutor)")
            .addStatement("throw rethrow(disconnectFailure)")
            .endControlFlow()
            .addStatement("closeReturningToPool(connection)")
            .addJavadoc("Unmounts identity and releases the connection, settling any transaction the operation\n"
                + "left open first so the disconnect hook runs outside any transaction. Fires disconnect on\n"
                + "every completion path (success, error, cancellation); idempotent, so a redundant\n"
                + "cancel-then-complete release unmounts exactly once. Evicts on disconnect failure (and on\n"
                + "a failed pre-disconnect settle, which equally leaves identity unprovable).\n")
            .build();

        var close = MethodSpec.methodBuilder("close")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addStatement("release()")
            .addJavadoc("{@link AutoCloseable} alias for {@link #release()}.\n")
            .build();

        var afterSettle = MethodSpec.methodBuilder("afterSettle")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .beginControlFlow("if (!remountAfterSettle || released)")
            .addStatement("return")
            .endControlFlow()
            .beginControlFlow("try")
            .addComment("Re-fire the pair: unmount the old handle, remount fresh. Runs in autocommit (the")
            .addComment("provider restored it before this callback), so the remount commits immediately.")
            .addStatement("hook.disconnect(connection, handle)")
            .addStatement("handle = hook.connect(connection, claims)")
            .nextControlFlow("catch ($T remountFailure)", Throwable.class)
            .addComment("Identity is now unprovable and graphql-java runs later mutation fields serially")
            .addComment("after a failed one: evict immediately so their SQL fails on a dead connection")
            .addComment("rather than running with unknown identity. release() becomes a no-op.")
            .addStatement("released = true")
            .addStatement("evict(connection, abortExecutor)")
            .addStatement("throw rethrow(remountFailure)")
            .endControlFlow()
            .addJavadoc("Settle-completion hook the transaction provider runs after each top-level settle:\n"
                + "when the {@code <sessionState>} config did not declare survival\n"
                + "({@code <stateSurvivesTransactions>true</>}), the mounted state may not have survived the\n"
                + "commit or rollback, so the hook pair re-fires (disconnect the old handle, connect a fresh\n"
                + "one) and the read-back projections and later mutation fields see remounted identity.\n"
                + "No-op when survival is declared, structural (the {@code <variables>} sugar), or there is\n"
                + "no hook. A remount failure evicts immediately: identity that cannot be proven mounted\n"
                + "must not serve the operation's remaining SQL.\n")
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

        // Propagate unchecked causes with their type intact; wrap the checked residue (a connect/disconnect
        // SQLException) in an unchecked carrier so release() needs no throws clause. Errors are rethrown
        // directly. Returns the RuntimeException to throw so both call sites read `throw rethrow(...)`, which
        // also tells the compiler control does not fall through.
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
                + "residue (a connect/disconnect {@code SQLException}) is wrapped. Callers write\n"
                + "{@code throw rethrow(cause)}.\n")
            .build();

        var builder = TypeSpec.classBuilder(PINNED_CONNECTION_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(AutoCloseable.class)
            .addJavadoc("One pinned connection with per-request identity mounted for its acquisition-scoped\n"
                + "lifetime. Carries no transaction machinery (the {@code TransactionProvider} and\n"
                + "execution instrumentation compose over this seam); {@link #afterSettle} is the one\n"
                + "identity-side hook the provider triggers, through an opaque callback, after each\n"
                + "top-level settle. See {@code ConnectionRuntimeClassGenerator} for the full lifecycle\n"
                + "contract.\n")
            .addField(connectionField)
            .addField(hookField)
            .addField(claimsField)
            .addField(handleField)
            .addField(remountAfterSettleField)
            .addField(abortExecutorField)
            .addField(releasedField)
            .addMethod(constructor)
            .addMethod(acquire)
            .addMethod(connectionAccessor)
            .addMethod(release);
        if (multiTenant) {
            builder.addMethod(abortMethod());
        }
        return builder
            .addMethod(close)
            .addMethod(afterSettle)
            .addMethod(evict)
            .addMethod(closeReturningToPool)
            .addMethod(rethrow)
            .build();
    }

    /**
     * {@code abort()}: the straggler release path. A scatter worker past its deadline may still be
     * mid-statement on this connection, so neither the disconnect hook (a second concurrent user of
     * the connection) nor {@code close()} (returns a possibly-live connection to the pool) is safe;
     * {@code Connection.abort} is the JDBC primitive designed for exactly this. {@code synchronized}
     * because a straggler worker's self-abort and the dispatch thread's {@code releaseAll} can race
     * on the same instance; {@code release()} needs no synchronization (release and abort never
     * target the same instance — the carrier's per-key remove arbitrates which path processes an
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
     * The application-scoped runtime holding the DataSource, dialect, and baked session hook.
     * Multi-tenant builds additionally carry the fan-out execution configuration (the bounded
     * scatter executor and the scatter deadline), as two flat constructor scalars plus an optional
     * consumer-supplied {@code Executor} overload; deployment-time values, so they never touch the
     * Mojo. Single-tenant emission is byte-identical to the pre-fan-out shape.
     */
    private static TypeSpec runtime(ClassName sessionHook, ClassName pinnedConnection, ClassName instrumentation,
                                    RuntimeHookProjection projection, TypeName tenantKey, boolean multiTenant) {
        CodeBlock hookInitializer = projection.hookInitializer();
        boolean requiresPostgres = projection.requiresPostgres();
        var dataSourceField = FieldSpec.builder(DATA_SOURCE, "dataSource", Modifier.PRIVATE, Modifier.FINAL).build();
        var tenantSourcesField = FieldSpec.builder(
                ParameterizedTypeName.get(MAP, tenantKey, DATA_SOURCE),
                "dataSourcesByTenant", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("Per-tenant {@code DataSource}s for database-per-tenant routing; empty for the\n"
                + "single-tenant runtime. Keyed by the divined tenant value; the key type is read off the\n"
                + "catalog's tenant column when {@code <tenantColumn>} is configured, {@code Object}\n"
                + "otherwise (the key type is a classification concern, not the lifecycle's).\n")
            .build();
        var dialectField = FieldSpec.builder(SQL_DIALECT, "dialect", Modifier.PRIVATE, Modifier.FINAL).build();
        var hookField = FieldSpec.builder(sessionHook, "sessionHook", Modifier.PRIVATE, Modifier.FINAL).build();
        // Same-thread executor for Connection.abort(); the abort work is trivial and must complete before
        // the borrow returns, so there is no reason to hand it to another thread.
        var abortExecutorField = FieldSpec.builder(EXECUTOR, "abortExecutor", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("$T::run", Runnable.class)
            .build();
        // Fan-out execution configuration (multi-tenant builds only): the bounded scatter executor
        // and the scatter deadline. A second, independent executor beside abortExecutor; the two are
        // orthogonal and never conflated.
        var defaultFanOutConcurrencyField = FieldSpec.builder(int.class, "DEFAULT_FAN_OUT_CONCURRENCY",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("8")
            .addJavadoc("Default scatter concurrency cap: tenants in flight per fanned field.\n")
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

        var mapParamType = ParameterizedTypeName.get(MAP, WildcardTypeName.subtypeOf(tenantKey), DATA_SOURCE);

        // Canonical constructor: default source (untenanted / single-tenant) plus the per-tenant map. The
        // two-arg single-tenant form delegates here with an empty map. In multi-tenant builds the
        // canonical grows the two fan-out slots (executor + deadline); the map-only form delegates
        // with the documented defaults.
        var canonicalBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(DATA_SOURCE, "defaultDataSource")
            .addParameter(mapParamType, "dataSourcesByTenant")
            .addParameter(SQL_DIALECT, "dialect");
        if (multiTenant) {
            canonicalBuilder
                .addParameter(EXECUTOR, "fanOutExecutor")
                .addParameter(DURATION, "fanOutTimeout");
        }
        canonicalBuilder
            .addStatement("this.dataSource = $T.requireNonNull(defaultDataSource, $S)", OBJECTS, "defaultDataSource")
            .addStatement("this.dataSourcesByTenant = new $T<>($T.requireNonNull(dataSourcesByTenant, $S))",
                LINKED_HASH_MAP, OBJECTS, "dataSourcesByTenant")
            .addStatement("this.dialect = $T.requireNonNull(dialect, $S)", OBJECTS, "dialect");
        if (multiTenant) {
            canonicalBuilder
                .addStatement("this.fanOutExecutor = $T.requireNonNull(fanOutExecutor, $S)", OBJECTS, "fanOutExecutor")
                .addStatement("this.fanOutTimeout = $T.requireNonNull(fanOutTimeout, $S)", OBJECTS, "fanOutTimeout")
                .beginControlFlow("if (fanOutTimeout.isZero() || fanOutTimeout.isNegative())")
                .addStatement("throw new $T($S + fanOutTimeout)", IllegalArgumentException.class,
                    "fanOutTimeout must be positive, got: ")
                .endControlFlow();
        }
        if (requiresPostgres) {
            // The <variables> sugar bakes PostgreSQL set_config statements at build time, but the dialect
            // arrives only here at construction; guard fail-closed so a mismatched dialect fails loudly at
            // wiring time rather than as a first-request SQL error days after the build passed.
            canonicalBuilder
                .beginControlFlow("if (dialect.family() != $T.POSTGRES)", SQL_DIALECT)
                .addStatement("throw new $T($S + dialect + $S)", IllegalStateException.class,
                    "The <sessionState> <variables> sugar generates PostgreSQL set_config statements, but the "
                        + "configured dialect is ",
                    "; use the <connect>/<disconnect> function-hook form for other dialects.")
                .endControlFlow();
        }
        var canonicalConstructor = canonicalBuilder
            .addStatement("this.sessionHook = $L", hookInitializer)
            .addJavadoc("Builds the runtime over a default {@code DataSource} (untenanted / single-tenant SQL)\n"
                + "and a per-tenant map for database-per-tenant routing (the tenant-routing construction\n"
                + "overload). The\n"
                + "consumer (or their framework) still owns pool creation and tuning.\n"
                + "@param defaultDataSource source for untenanted SQL; must not be {@code null}\n"
                + "@param dataSourcesByTenant per-tenant sources keyed by divined tenant value; may be empty\n"
                + "@param dialect the jOOQ {@code SQLDialect} for this database; must not be {@code null}\n"
                + (multiTenant
                    ? "@param fanOutExecutor the executor scatter workers run on; the supplier owns its\n"
                        + "concurrency bound (e.g. virtual threads); must not be {@code null}\n"
                        + "@param fanOutTimeout the per-scatter deadline, enforced by the join whichever\n"
                        + "executor runs the workers; must be positive\n"
                    : ""))
            .build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(DATA_SOURCE, "dataSource")
            .addParameter(SQL_DIALECT, "dialect")
            .addStatement("this(dataSource, $T.of(), dialect)", MAP)
            .addJavadoc("Builds the single-tenant runtime over one consumer-owned {@code DataSource} and\n"
                + "dialect (no per-tenant routing). The runtime owns acquisition, transactions, and identity\n"
                + "on top of the consumer's pool.\n"
                + "@param dataSource the consumer's pooled {@code DataSource}; must not be {@code null}\n"
                + "@param dialect the jOOQ {@code SQLDialect} for this database; must not be {@code null}\n")
            .build();

        // Multi-tenant fan-out configuration: two delegating overloads over the executor-form
        // canonical. The map-only form bakes the documented defaults; the int-cap form builds the
        // default bounded pool of platform threads sized by the cap. Deployment-time values, so the
        // constructor surface (not the Mojo) is where they live.
        var mapOnlyConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(DATA_SOURCE, "defaultDataSource")
            .addParameter(mapParamType, "dataSourcesByTenant")
            .addParameter(SQL_DIALECT, "dialect")
            .addStatement("this(defaultDataSource, dataSourcesByTenant, dialect, DEFAULT_FAN_OUT_CONCURRENCY,"
                + " DEFAULT_FAN_OUT_TIMEOUT)")
            .addJavadoc("Builds the tenant-routing runtime with the default fan-out configuration:\n"
                + "{@link #DEFAULT_FAN_OUT_CONCURRENCY} scatter workers in flight and the\n"
                + "{@link #DEFAULT_FAN_OUT_TIMEOUT} scatter deadline.\n"
                + "@param defaultDataSource source for untenanted SQL; must not be {@code null}\n"
                + "@param dataSourcesByTenant per-tenant sources keyed by divined tenant value; may be empty\n"
                + "@param dialect the jOOQ {@code SQLDialect} for this database; must not be {@code null}\n")
            .build();
        var cappedConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(DATA_SOURCE, "defaultDataSource")
            .addParameter(mapParamType, "dataSourcesByTenant")
            .addParameter(SQL_DIALECT, "dialect")
            .addParameter(int.class, "fanOutConcurrency")
            .addParameter(DURATION, "fanOutTimeout")
            .addStatement("this(defaultDataSource, dataSourcesByTenant, dialect,"
                + " boundedFanOutPool(fanOutConcurrency), fanOutTimeout)")
            .addJavadoc("Builds the tenant-routing runtime with an explicit fan-out cap and deadline; the\n"
                + "runtime owns a bounded pool of platform threads sized by the cap. To own threading\n"
                + "yourself (e.g. virtual threads), use the {@code Executor}-form constructor instead.\n"
                + "@param defaultDataSource source for untenanted SQL; must not be {@code null}\n"
                + "@param dataSourcesByTenant per-tenant sources keyed by divined tenant value; may be empty\n"
                + "@param dialect the jOOQ {@code SQLDialect} for this database; must not be {@code null}\n"
                + "@param fanOutConcurrency the maximum scatter workers in flight; at least 1\n"
                + "@param fanOutTimeout the per-scatter deadline; must be positive\n")
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
            .addStatement("return dataSourcesByTenant.keySet()")
            .addJavadoc("The configured tenant keys in the map's configured order (the constructor copies\n"
                + "into a {@code LinkedHashMap}), so the fan-out domain's concatenation order is\n"
                + "deployment-stable. Read-only view.\n")
            .build();

        var dialectAccessor = MethodSpec.methodBuilder("dialect")
            .addModifiers(Modifier.PUBLIC)
            .returns(SQL_DIALECT)
            .addStatement("return dialect")
            .addJavadoc("The configured jOOQ {@code SQLDialect}.\n")
            .build();

        var acquire = MethodSpec.methodBuilder("acquire")
            .addModifiers(Modifier.PUBLIC)
            .returns(pinnedConnection)
            .addParameter(String.class, "claims")
            .addException(SQL_EXCEPTION)
            .addStatement("return $T.acquire(dataSource, sessionHook, claims, abortExecutor, $L)",
                pinnedConnection, projection.remountAfterSettle())
            .addJavadoc("Pins one connection from the {@code DataSource} and mounts identity on it from the\n"
                + "opaque {@code claims} payload. Fail-closed. The caller releases the returned\n"
                + "{@code PinnedConnection} exactly once at operation completion; the execution\n"
                + "instrumentation wired by {@link #newGraphQL} does this, so consumers register nothing.\n"
                + "The settle re-fire literal is baked from the {@code <sessionState>} config.\n"
                + "@param claims the opaque per-request claims payload (typically the JWT), never parsed here\n")
            .build();

        var acquireForTenant = MethodSpec.methodBuilder("acquireForTenant")
            .addModifiers(Modifier.PUBLIC)
            .returns(pinnedConnection)
            .addParameter(tenantKey, "tenantKey")
            .addParameter(String.class, "claims")
            .addException(SQL_EXCEPTION)
            .addStatement("$T tenantDataSource = dataSourcesByTenant.get(tenantKey)", DATA_SOURCE)
            .beginControlFlow("if (tenantDataSource == null)")
            .addComment("Unknown divined tenant: a request-level error before any SQL. Distinct from the")
            .addComment("acquisition-failure family so callers can map it structurally, not by message.")
            .addStatement("throw new $T($S + tenantKey)", NO_SUCH_ELEMENT, "No DataSource configured for tenant key: ")
            .endControlFlow()
            .addStatement("return $T.acquire(tenantDataSource, sessionHook, claims, abortExecutor, $L)",
                pinnedConnection, projection.remountAfterSettle())
            .addJavadoc("Pins one connection from the {@code tenantKey}'s {@code DataSource} and mounts identity\n"
                + "on it, for database-per-tenant routing. An unknown key raises\n"
                + "{@link java.util.NoSuchElementException} before any connection is acquired (request-level\n"
                + "error, no SQL). Per-key deduplication within one operation is the caller's ({@code $L}); this\n"
                + "is the raw keyed acquisition primitive.\n"
                + "@param tenantKey the divined tenant value selecting the source\n"
                + "@param claims the opaque per-request claims payload, never parsed here\n", TENANT_CONNECTIONS_CLASS_NAME)
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
                + "instrumentation already attached: every operation pins a connection, mounts identity, runs\n"
                + "in an operation-typed transaction, and releases at completion, with no registration by the\n"
                + "consumer. This is the owned-connection engine assembly; pair it with\n"
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
                + "time via {@code Graphitron.runtime(dataSource, dialect)}, it pins one connection per\n"
                + "operation, mounts and unmounts per-request identity through the {@link $T} seam, and\n"
                + "demarcates operation-typed transactions (via the instrumentation {@link #newGraphQL} attaches).\n"
                + "Holds no per-request state.\n", sessionHook);
        if (multiTenant) {
            builder.addField(defaultFanOutConcurrencyField)
                .addField(defaultFanOutTimeoutField);
        }
        builder.addField(dataSourceField)
            .addField(tenantSourcesField)
            .addField(dialectField)
            .addField(hookField)
            .addField(abortExecutorField);
        if (multiTenant) {
            builder.addField(fanOutExecutorField)
                .addField(fanOutTimeoutField);
        }
        builder.addMethod(canonicalConstructor);
        if (multiTenant) {
            builder.addMethod(cappedConstructor)
                .addMethod(mapOnlyConstructor);
        }
        builder.addMethod(constructor)
            .addMethod(dialectAccessor)
            .addMethod(acquire)
            .addMethod(acquireForTenant)
            .addMethod(newGraphQL);
        if (multiTenant) {
            builder.addMethod(fanOutExecutorAccessor)
                .addMethod(fanOutTimeoutAccessor)
                .addMethod(tenantKeysAccessor)
                .addMethod(boundedFanOutPool);
        }
        return builder.build();
    }

    /**
     * The per-operation tenant-keyed connection carrier (slice 4): generalizes slice 2's single pinned
     * connection to one pinned connection per <em>distinct</em> divined tenant key within an operation.
     * {@code dslFor(key)} pins-and-mounts on first use of a key and reuses thereafter (so N distinct keys
     * pin N connections, a repeated key pins once), binding a provider-backed {@code DSLContext} over the
     * key's connection; {@code releaseAll()} releases every pinned connection on every completion path,
     * per-connection eviction on disconnect failure, idempotent.
     *
     * <p>The {@code DSL.using(...) + TransactionProvider} binding lives here, single-sourced, so the
     * many per-field tenant-routing sites consume {@code dslFor(key)} as a drop-in for {@code getDslContext(env)}
     * and never re-emit the binding (only <em>which key</em> and <em>where it routes</em> are schema-shaped).
     *
 * <p>Forward note: this carrier subsumes slice 2's single-{@code pinned} instrumentation state
     * as the one-entry case; when tenant routing is wired in, the untenanted path becomes a default-key
     * entry here rather than a second parallel carrier, collapsing the two release-on-completion sites into
     * one. Slice 4 lands and proves the carrier directly (test-supplied keys, fake tenant map); it does not
     * rewire the instrumentation.
     */
    private static TypeSpec tenantConnections(ClassName self, ClassName runtime, ClassName pinnedConnection,
                                              ClassName provider, ClassName commitPolicy, TypeName tenantKey,
                                              boolean multiTenant) {
        var pinnedMapType = ParameterizedTypeName.get(MAP, tenantKey, pinnedConnection);

        var runtimeField = FieldSpec.builder(runtime, "runtime", Modifier.PRIVATE, Modifier.FINAL).build();
        var claimsField = FieldSpec.builder(String.class, "claims", Modifier.PRIVATE, Modifier.FINAL).build();
        var policyField = FieldSpec.builder(commitPolicy, "commitPolicy", Modifier.PRIVATE, Modifier.FINAL).build();
        var pinnedField = multiTenant
            ? FieldSpec.builder(pinnedMapType, "pinnedByTenant", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", CONCURRENT_HASH_MAP)
                .addJavadoc("Concurrent with per-key single acquisition ({@code computeIfAbsent}): scatter\n"
                    + "partitions distinct keys one worker each, but nothing structural prevents a worker and\n"
                    + "the dispatch thread racing the same key, so one-pin-per-key is this map's contract,\n"
                    + "not an accident of the callers.\n")
                .build()
            : FieldSpec.builder(pinnedMapType, "pinnedByTenant", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", LINKED_HASH_MAP)
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
        var defaultPinnedField = FieldSpec.builder(pinnedConnection, "defaultPinned", Modifier.PRIVATE)
            .addJavadoc("The default-source pinned connection serving untenanted SQL, pinned on first\n"
                + "{@link #dslDefault()} use. A field rather than a reserved map key: the map is keyed by\n"
                + "the typed divined tenant value, and no tenant value means the default source.\n"
                + "Needs no concurrency work: scatter workers cannot reach it ({@code perTenant} receives\n"
                + "only the keyed {@code DSLContext}, structurally), and the dispatch thread that owns it\n"
                + "is blocked in the scatter join while workers run, so its check-then-pin stays a serial\n"
                + "code path.\n")
            .build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(runtime, "runtime")
            .addParameter(String.class, "claims")
            .addParameter(commitPolicy, "commitPolicy")
            .addStatement("this.runtime = runtime")
            .addStatement("this.claims = claims")
            .addStatement("this.commitPolicy = commitPolicy")
            .addJavadoc(multiTenant
                ? "Builds a per-operation carrier over {@code runtime} for one request's {@code claims}\n"
                    + "and commit policy. One instance per operation. Concurrency is confined to\n"
                    + "{@link #scatter}'s bounded workers, each owning one keyed connection single-threaded\n"
                    + "through {@link #dslFor}, with the dispatch thread blocked on the join for the\n"
                    + "scatter's whole duration; every other access runs serially on the dispatch thread.\n"
                : "Builds a per-operation carrier over {@code runtime} for one request's {@code claims}\n"
                    + "and commit policy. One instance per operation; not thread-safe (a single operation's\n"
                    + "fetchers run serially on the dispatch thread).\n")
            .build();

        var dslForBuilder = MethodSpec.methodBuilder("dslFor")
            .addModifiers(Modifier.PUBLIC)
            .returns(DSL_CONTEXT)
            .addParameter(tenantKey, "tenantKey")
            .addException(SQL_EXCEPTION);
        if (multiTenant) {
            dslForBuilder
                .beginControlFlow("if (timedOutTenants.contains(tenantKey))")
                .addComment("The key's scatter worker missed the join deadline; its connection may still be")
                .addComment("executing, so it is never reused within the operation.")
                .addStatement("throw new $T($S + tenantKey + $S)", IllegalStateException.class,
                    "Tenant '", "' timed out earlier in this operation; its connection is never reused.")
                .endControlFlow()
                .addStatement("$T pinned", pinnedConnection)
                .beginControlFlow("try")
                .addComment("Per-key single acquisition, also under concurrent scatter workers: exactly one pin")
                .addComment("per key even when a worker and the dispatch thread race the same key. The checked")
                .addComment("acquisition failure tunnels out of the compute lambda unchanged.")
                .addCode("pinned = pinnedByTenant.computeIfAbsent(tenantKey, key -> {\n")
                .addCode("    try {\n")
                .addCode("        return runtime.acquireForTenant(key, claims);\n")
                .addCode("    } catch ($T e) {\n", SQL_EXCEPTION)
                .addCode("        throw new $T(e);\n", COMPLETION_EXCEPTION)
                .addCode("    }\n")
                .addCode("});\n")
                .nextControlFlow("catch ($T e)", COMPLETION_EXCEPTION)
                .beginControlFlow("if (e.getCause() instanceof $T sql)", SQL_EXCEPTION)
                .addStatement("throw sql")
                .endControlFlow()
                .addStatement("throw e")
                .endControlFlow()
                .beginControlFlow("if (closed || timedOutTenants.contains(tenantKey))")
                .addComment("The operation moved on (released, or this key's join deadline passed) while the pin")
                .addComment("was in flight; never hand out a connection the release path can no longer own.")
                .addStatement("$T stale = pinnedByTenant.remove(tenantKey)", pinnedConnection)
                .beginControlFlow("if (stale != null)")
                .addStatement("stale.abort()")
                .endControlFlow()
                .addStatement("throw new $T($S + tenantKey + $S)", IllegalStateException.class,
                    "Tenant '", "' was pinned after its scatter deadline or after the operation completed.")
                .endControlFlow();
        } else {
            dslForBuilder
                .addStatement("$T pinned = pinnedByTenant.get(tenantKey)", pinnedConnection)
                .beginControlFlow("if (pinned == null)")
                .addComment("First use of this key in the operation: pin one connection and mount identity on it.")
                .addStatement("pinned = runtime.acquireForTenant(tenantKey, claims)")
                .addStatement("pinnedByTenant.put(tenantKey, pinned)")
                .endControlFlow();
        }
        var dslFor = dslForBuilder
            .addStatement("$T connection = pinned.connection()", CONNECTION)
            .addComment("Bind a DSLContext to the pinned connection and swap in the transaction provider, the")
            .addComment("same recipe slice 2 uses for the single-connection path. jOOQ's single-connection")
            .addComment("provider treats release as a no-op, so the runtime keeps sole ownership of close/evict.")
            .addComment("The settle callback re-fires unconfirmed session hooks after each per-field settle.")
            .addStatement("$T dsl = $T.using(connection, runtime.dialect())", DSL_CONTEXT, DSL)
            .addStatement("dsl.configuration().set(new $T(connection, commitPolicy, pinned::afterSettle))", provider)
            .addStatement("return dsl")
            .addJavadoc("Returns the provider-bound {@code DSLContext} for {@code tenantKey}, pinning and\n"
                + "mounting one connection for the key on first use and reusing it thereafter. A drop-in for\n"
                + "{@code getDslContext(env)} at a routed fetcher site.\n"
                + "@param tenantKey the divined tenant value; an unknown key raises before any SQL\n")
            .build();

        var dslDefault = MethodSpec.methodBuilder("dslDefault")
            .addModifiers(Modifier.PUBLIC)
            .returns(DSL_CONTEXT)
            .addException(SQL_EXCEPTION)
            .beginControlFlow("if (defaultPinned == null)")
            .addComment("First untenanted SQL in the operation: pin one default-source connection.")
            .addStatement("defaultPinned = runtime.acquire(claims)")
            .endControlFlow()
            .addStatement("$T connection = defaultPinned.connection()", CONNECTION)
            .addStatement("$T dsl = $T.using(connection, runtime.dialect())", DSL_CONTEXT, DSL)
            .addStatement("dsl.configuration().set(new $T(connection, commitPolicy, defaultPinned::afterSettle))", provider)
            .addStatement("return dsl")
            .addJavadoc("Returns the provider-bound {@code DSLContext} for the default source (untenanted\n"
                + "SQL: global reference data), pinning and mounting one connection on first use and\n"
                + "reusing it thereafter. The untenanted sibling of {@link #dslFor}.\n")
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
                .beginControlFlow("if (defaultPinned != null)")
                .beginControlFlow("try")
                .addStatement("defaultPinned.release()")
                .nextControlFlow("catch ($T e)", RuntimeException.class)
                .addStatement("failure = e")
                .endControlFlow()
                .addStatement("defaultPinned = null")
                .endControlFlow()
                .beginControlFlow("for ($T key : pinnedByTenant.keySet())", tenantKey)
                .addComment("remove() arbitrates each entry to exactly one processor, against a straggler's")
                .addComment("concurrent self-abort of the same entry.")
                .addStatement("$T pinned = pinnedByTenant.remove(key)", pinnedConnection)
                .beginControlFlow("if (pinned == null)")
                .addStatement("continue")
                .endControlFlow()
                .beginControlFlow("if (timedOutTenants.contains(key))")
                .addComment("A TimedOut outcome means the join stopped waiting, not that the worker stopped")
                .addComment("working: the connection may still be mid-statement. A JDBC call cannot be safely")
                .addComment("killed, so route the straggler through the abort seam; never close or return a")
                .addComment("connection whose worker may still be executing on it.")
                .addStatement("pinned.abort()")
                .addStatement("continue")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("pinned.release()")
                .nextControlFlow("catch ($T e)", RuntimeException.class)
                .addComment("release() already evicted this connection on disconnect failure; keep releasing the")
                .addComment("rest so one tenant's failed unmount never orphans another's connection.")
                .beginControlFlow("if (failure == null)")
                .addStatement("failure = e")
                .endControlFlow()
                .endControlFlow()
                .endControlFlow();
        } else {
            releaseAllBuilder
                .beginControlFlow("for ($T pinned : pinnedByTenant.values())", pinnedConnection)
                .beginControlFlow("try")
                .addStatement("pinned.release()")
                .nextControlFlow("catch ($T e)", RuntimeException.class)
                .addComment("release() already evicted this connection on disconnect failure; keep releasing the")
                .addComment("rest so one tenant's failed unmount never orphans another's connection.")
                .beginControlFlow("if (failure == null)")
                .addStatement("failure = e")
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                .addStatement("pinnedByTenant.clear()");
        }
        var releaseAll = releaseAllBuilder
            .beginControlFlow("if (failure != null)")
            .addStatement("throw failure")
            .endControlFlow()
            .addJavadoc(multiTenant
                ? "Releases every pinned connection on every completion path (success, error,\n"
                    + "cancellation): each {@code release()} unmounts identity and returns or evicts its own\n"
                    + "connection, and one tenant's disconnect failure does not orphan the others. Idempotent:\n"
                    + "the map is drained, so a redundant call is a no-op. Rethrows the first release failure\n"
                    + "after attempting them all. A tenant whose scatter worker missed the join deadline is\n"
                    + "routed through {@code PinnedConnection.abort()}: its worker may still be executing, so\n"
                    + "the connection is evicted, never closed under a live statement nor returned to the pool.\n"
                : "Releases every pinned connection on every completion path (success, error,\n"
                    + "cancellation): each {@code release()} unmounts identity and returns or evicts its own\n"
                    + "connection, and one tenant's disconnect failure does not orphan the others. Idempotent:\n"
                    + "the map is cleared, so a redundant call is a no-op. Rethrows the first release failure after\n"
                    + "attempting them all.\n")
            .build();

        var carrier = TypeSpec.classBuilder(TENANT_CONNECTIONS_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc(multiTenant
                ? "Per-operation carrier of the tenant-keyed pinned connections for one request. See\n"
                    + "{@code ConnectionRuntimeClassGenerator} for the full contract. Concurrency is confined\n"
                    + "to {@link #scatter}'s bounded workers; everything else runs serially on the dispatch\n"
                    + "thread.\n"
                : "Per-operation carrier of the tenant-keyed pinned connections for one request. See\n"
                    + "{@code ConnectionRuntimeClassGenerator} for the full contract.\n");
        if (multiTenant) {
            carrier.addField(scatterWorkerMarkerField);
        }
        carrier.addField(runtimeField)
            .addField(claimsField)
            .addField(policyField)
            .addField(pinnedField)
            .addMethod(constructor)
            .addMethod(dslFor);
        // The default-source arm exists only when <tenantColumn> is configured: absent the
        // element, none of the routing machinery below exists and the carrier stays the plain
        // keyed-acquisition surface shipped with the connection lifecycle.
        if (multiTenant) {
            carrier.addField(defaultPinnedField)
                .addField(timedOutField)
                .addField(closedField)
                .addMethod(dslDefault);
        }
        carrier.addMethod(releaseAll);
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
                .addMethod(ofEnvironment(self))
                .addMethod(staticDslFor(self, tenantKey))
                .addMethod(staticDslDefault(self))
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
            .addStatement("return new FanOutFailure(correlationId.toString(), $S)", "TenantFanOutFailed")
            .endControlFlow()
            .addStatement("LOGGER.error($S, outcome.key(), correlationId)",
                "Tenant fan-out timed out for tenant {}; correlation id = {}")
            .addStatement("return new FanOutFailure(correlationId.toString(), $S)", "TenantFanOutTimedOut")
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
            .beginControlFlow("for ($T key : order)", tenantKey)
            .addStatement("futures.add($T.supplyAsync(() -> scatterWorker(key, perTenant), runtime.fanOutExecutor()))",
                COMPLETABLE_FUTURE)
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
            .addStatement("$T.currentThread().interrupt()", Thread.class)
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
     * routed acquisition emitted fetcher sites use — resolves the carrier off the GraphQL
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
     * carrier the execution instrumentation stashed in the GraphQL context. Multi-tenant builds
     * only; emitted fetchers route every acquisition through this, so an operation that did not
     * run through graphitron-owned acquisition fails loudly before any SQL instead of silently
     * targeting the wrong database.
     */
    private static MethodSpec ofEnvironment(ClassName self) {
        return MethodSpec.methodBuilder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(self)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("$T tenants = env.getGraphQlContext().get($T.class)", self, self)
            .beginControlFlow("if (tenants == null)")
            .addStatement("throw new $T($S)", IllegalStateException.class,
                "No " + TENANT_CONNECTIONS_CLASS_NAME + " in the GraphQL context: a multi-tenant build routes"
                    + " connections per divined tenant, so the operation must run through graphitron-owned"
                    + " acquisition (newOwnedExecutionInput / GraphitronRuntime.newGraphQL).")
            .endControlFlow()
            .addStatement("return tenants")
            .addJavadoc("The per-operation carrier the execution instrumentation stashed in the GraphQL\n"
                + "context. Fails loudly when the operation did not run through graphitron-owned\n"
                + "acquisition; routed fetchers never fall back to an unrouted connection.\n"
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
     * Emits the concrete {@code SessionHook} baked into the runtime, or {@code null} for {@link None}
     * (the runtime uses {@code SessionHook.NONE}). Forks on an exhaustive {@code switch} over the sealed
     * config so a fourth form is a compile error here.
     */
    private static TypeSpec sessionHookImpl(ClassName sessionHook, SessionStateConfig config) {
        return switch (config) {
            case None ignored -> null;
            case FunctionHooks fh -> functionHookImpl(sessionHook, fh);
            case Variables v -> variablesHookImpl(sessionHook, v);
        };
    }

    /** The function-hook form: connect/disconnect call consumer-authored DB callables via {@link java.sql.CallableStatement}. */
    private static TypeSpec functionHookImpl(ClassName sessionHook, FunctionHooks fh) {
        boolean producesHandle = fh.unmount() instanceof Unmount.PairedDisconnect pd && pd.handle();

        String connectSql = "{ call " + fh.connectCall() + "(?" + (producesHandle ? ", ?" : "") + ") }";
        var connect = MethodSpec.methodBuilder("connect")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(String.class)
            .addParameter(CONNECTION, "connection")
            .addParameter(String.class, "claims")
            .addException(SQL_EXCEPTION)
            .beginControlFlow("try ($T cs = connection.prepareCall($S))", CALLABLE_STATEMENT, connectSql)
            .addStatement("cs.setString(1, claims)");
        if (producesHandle) {
            connect.addStatement("cs.registerOutParameter(2, $T.VARCHAR)", JDBC_TYPES)
                .addStatement("cs.execute()")
                .addStatement("return cs.getString(2)");
        } else {
            connect.addStatement("cs.execute()")
                .addStatement("return null");
        }
        var connectMethod = connect.endControlFlow()
            .addJavadoc("Mounts identity by calling {@code $L}, passing the opaque claims payload.$L\n",
                fh.connectCall(),
                producesHandle ? " Captures the OUT handle it returns." : "")
            .build();

        var disconnect = functionDisconnect(fh.unmount());

        return TypeSpec.classBuilder(SESSION_HOOK_IMPL_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(sessionHook)
            .addJavadoc("Generated {@code SessionHook} for the function-hook {@code <sessionState>} form:\n"
                + "connect calls {@code $L} and disconnect $L. Both are consumer-authored database callables;\n"
                + "graphitron only guarantees the pair runs at mount and unmount.\n",
                fh.connectCall(),
                fh.unmount() instanceof Unmount.PairedDisconnect pd ? "calls {@code " + pd.call() + "}" : "is the unmount-free opt-out")
            .addMethod(connectMethod)
            .addMethod(disconnect)
            .build();
    }

    /** The disconnect half of the function-hook form: a paired callable, or the unmount-free no-op. */
    private static MethodSpec functionDisconnect(Unmount unmount) {
        var disconnect = MethodSpec.methodBuilder("disconnect")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(CONNECTION, "connection")
            .addParameter(String.class, "handle")
            .addException(SQL_EXCEPTION);
        return switch (unmount) {
            case Unmount.PairedDisconnect pd -> {
                String disconnectSql = "{ call " + pd.call() + "(" + (pd.handle() ? "?" : "") + ") }";
                disconnect.beginControlFlow("try ($T cs = connection.prepareCall($S))", CALLABLE_STATEMENT, disconnectSql);
                if (pd.handle()) {
                    disconnect.addStatement("cs.setString(1, handle)");
                }
                yield disconnect.addStatement("cs.execute()")
                    .endControlFlow()
                    .addJavadoc("Unmounts identity by calling {@code $L}$L.\n",
                        pd.call(), pd.handle() ? ", bound to the handle connect returned" : "")
                    .build();
            }
            case Unmount.UnmountFree ignored -> disconnect
                .addComment("Unmount-free opt-out (empty <disconnect/>): connect mounts identity that this")
                .addComment("hook deliberately does not unmount. Slice 6's generation-time warning names this.")
                .addJavadoc("No-op: the {@code <sessionState>} config opted out of unmounting with an empty\n"
                    + "{@code <disconnect/>}. Identity mounted by connect is not unmounted here.\n")
                .build();
        };
    }

    /**
     * The Postgres {@code <variables>} sugar: both halves emitted from the one resolved variable set.
     * Connect issues one {@code set_config} per variable in a single round trip, reading each claim from
     * the payload JSON; disconnect clears exactly those variables to the empty string.
     */
    private static TypeSpec variablesHookImpl(ClassName sessionHook, Variables config) {
        var vars = config.variables();

        StringJoiner connectSets = new StringJoiner(", ", "select ", " from (select cast(? as jsonb) as c) claims");
        StringJoiner disconnectSets = new StringJoiner(", ", "select ", "");
        for (var v : vars) {
            connectSets.add("set_config('" + sqlLiteral(v.name()) + "', c ->> '" + sqlLiteral(v.claim()) + "', false)");
            disconnectSets.add("set_config('" + sqlLiteral(v.name()) + "', '', false)");
        }
        String connectSql = connectSets.toString();
        String disconnectSql = disconnectSets.toString();

        var connect = MethodSpec.methodBuilder("connect")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(String.class)
            .addParameter(CONNECTION, "connection")
            .addParameter(String.class, "claims")
            .addException(SQL_EXCEPTION)
            .beginControlFlow("try ($T ps = connection.prepareStatement($S))", PREPARED_STATEMENT, connectSql)
            .addStatement("ps.setString(1, claims)")
            .addStatement("ps.execute()")
            .endControlFlow()
            .addComment("The <variables> sugar carries no handle: session GUCs are cleared by name at disconnect.")
            .addStatement("return null")
            .addJavadoc("Mounts identity by setting each configured session variable from the claims JSON in a\n"
                + "single round trip. A claim absent from the payload sets the variable to the empty string,\n"
                + "which the RLS policy must treat as no identity (fail closed).\n")
            .build();

        var disconnect = MethodSpec.methodBuilder("disconnect")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(CONNECTION, "connection")
            .addParameter(String.class, "handle")
            .addException(SQL_EXCEPTION)
            .beginControlFlow("try ($T ps = connection.prepareStatement($S))", PREPARED_STATEMENT, disconnectSql)
            .addStatement("ps.execute()")
            .endControlFlow()
            .addJavadoc("Clears exactly the variables connect set, to the empty string. Emitted from the same\n"
                + "variable set as connect, so the two cannot drift.\n")
            .build();

        return TypeSpec.classBuilder(SESSION_HOOK_IMPL_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(sessionHook)
            .addJavadoc("Generated {@code SessionHook} for the Postgres {@code <variables>} {@code <sessionState>}\n"
                + "sugar: connect sets each session variable from the claims JSON, disconnect clears them. Both\n"
                + "halves are emitted from one resolved variable set, so disconnect clears exactly what connect\n"
                + "set. The runtime that bakes this hook fails closed when built with a non-Postgres dialect.\n")
            .addMethod(connect)
            .addMethod(disconnect)
            .build();
    }

    /** Escapes a value for embedding inside a single-quoted SQL string literal by doubling single quotes. */
    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }
}
