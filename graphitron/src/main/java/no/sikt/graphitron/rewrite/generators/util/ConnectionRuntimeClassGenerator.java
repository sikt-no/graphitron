package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
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
 * ({@code org.jooq.SQLDialect}); no auth-framework type and no graphitron type ever appears. The
 * bodies must be valid Java 17 (verified by the {@code graphitron-sakila-example}
 * {@code <release>17</release>} compile).
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
 * <h2>Load-bearing invariant: one connection per operation</h2>
 * Pinning exactly one connection per operation is safe only because generated batch loaders execute
 * SQL synchronously on the dispatch thread ({@code RowsMethodCall.batchLoaderLambda} emits
 * {@code CompletableFuture.completedFuture(rows(keys, dfe))}). That invariant is pinned at its
 * emission site by {@code RowsMethodCallTest}'s synchronous-body assertion; a future "make loaders
 * async" change fails there rather than as a distant execution-tier flake. See that test's javadoc,
 * which links back here.
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
    private static final ClassName SQL_DIALECT = ClassName.get("org.jooq", "SQLDialect");
    private static final ClassName OBJECTS = ClassName.get("java.util", "Objects");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName HASH_MAP = ClassName.get("java.util", "HashMap");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName NO_SUCH_ELEMENT = ClassName.get("java.util", "NoSuchElementException");
    private static final ParameterizedTypeName WILDCARD_DATASOURCE_MAP = ParameterizedTypeName.get(
        MAP, WildcardTypeName.subtypeOf(Object.class), DATA_SOURCE);
    private static final ParameterizedTypeName OBJECT_DATASOURCE_MAP = ParameterizedTypeName.get(
        MAP, ClassName.get(Object.class), DATA_SOURCE);
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");

    private ConnectionRuntimeClassGenerator() {}

    /**
     * @param outputPackage the consumer's root output package; the classes are emitted into
     *                      {@code outputPackage + ".schema"} (beside {@code GraphitronContext})
     * @param sessionState  the resolved {@code <sessionState>} config: {@link None} keeps
     *                      {@code SessionHook.NONE}; {@link FunctionHooks}/{@link Variables} additionally
     *                      emit a concrete {@link #SESSION_HOOK_IMPL_CLASS_NAME} the runtime bakes in
     */
    public static List<TypeSpec> generate(String outputPackage, SessionStateConfig sessionState) {
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

        var units = new ArrayList<TypeSpec>();
        units.add(sessionHook(sessionHook));
        units.add(pinnedConnection(pinnedConnection, sessionHook));
        units.add(runtime(sessionHook, pinnedConnection, instrumentation, projection));
        units.add(tenantConnections(tenantConnections, runtime, pinnedConnection, provider, commitPolicy));
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

    /** The acquisition-scoped pinned connection: acquire/release lifecycle with fail-closed + evict. */
    private static TypeSpec pinnedConnection(ClassName pinnedConnection, ClassName sessionHook) {
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

        return TypeSpec.classBuilder(PINNED_CONNECTION_CLASS_NAME)
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
            .addMethod(release)
            .addMethod(close)
            .addMethod(afterSettle)
            .addMethod(evict)
            .addMethod(closeReturningToPool)
            .addMethod(rethrow)
            .build();
    }

    /** The application-scoped runtime holding the DataSource, dialect, and baked session hook. */
    private static TypeSpec runtime(ClassName sessionHook, ClassName pinnedConnection, ClassName instrumentation,
                                    RuntimeHookProjection projection) {
        CodeBlock hookInitializer = projection.hookInitializer();
        boolean requiresPostgres = projection.requiresPostgres();
        var dataSourceField = FieldSpec.builder(DATA_SOURCE, "dataSource", Modifier.PRIVATE, Modifier.FINAL).build();
        var tenantSourcesField = FieldSpec.builder(OBJECT_DATASOURCE_MAP, "dataSourcesByTenant", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("Per-tenant {@code DataSource}s for database-per-tenant routing; empty for the\n"
                + "single-tenant runtime. Keyed by the divined tenant value, erased to {@code Object} because\n"
                + "the key type is a classification concern, not the lifecycle's.\n")
            .build();
        var dialectField = FieldSpec.builder(SQL_DIALECT, "dialect", Modifier.PRIVATE, Modifier.FINAL).build();
        var hookField = FieldSpec.builder(sessionHook, "sessionHook", Modifier.PRIVATE, Modifier.FINAL).build();
        // Same-thread executor for Connection.abort(); the abort work is trivial and must complete before
        // the borrow returns, so there is no reason to hand it to another thread.
        var abortExecutorField = FieldSpec.builder(EXECUTOR, "abortExecutor", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("$T::run", Runnable.class)
            .build();

        // Canonical constructor: default source (untenanted / single-tenant) plus the per-tenant map. The
        // two-arg single-tenant form delegates here with an empty map.
        var canonicalBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(DATA_SOURCE, "defaultDataSource")
            .addParameter(WILDCARD_DATASOURCE_MAP, "dataSourcesByTenant")
            .addParameter(SQL_DIALECT, "dialect")
            .addStatement("this.dataSource = $T.requireNonNull(defaultDataSource, $S)", OBJECTS, "defaultDataSource")
            .addStatement("this.dataSourcesByTenant = new $T<>($T.requireNonNull(dataSourcesByTenant, $S))",
                LINKED_HASH_MAP, OBJECTS, "dataSourcesByTenant")
            .addStatement("this.dialect = $T.requireNonNull(dialect, $S)", OBJECTS, "dialect");
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
                + "@param dialect the jOOQ {@code SQLDialect} for this database; must not be {@code null}\n")
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
            .addParameter(Object.class, "tenantKey")
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

        return TypeSpec.classBuilder(RUNTIME_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Application-scoped runtime that owns the connection lifecycle: built once at wiring\n"
                + "time via {@code Graphitron.runtime(dataSource, dialect)}, it pins one connection per\n"
                + "operation, mounts and unmounts per-request identity through the {@link $T} seam, and\n"
                + "demarcates operation-typed transactions (via the instrumentation {@link #newGraphQL} attaches).\n"
                + "Holds no per-request state.\n", sessionHook)
            .addField(dataSourceField)
            .addField(tenantSourcesField)
            .addField(dialectField)
            .addField(hookField)
            .addField(abortExecutorField)
            .addMethod(canonicalConstructor)
            .addMethod(constructor)
            .addMethod(dialectAccessor)
            .addMethod(acquire)
            .addMethod(acquireForTenant)
            .addMethod(newGraphQL)
            .build();
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
                                              ClassName provider, ClassName commitPolicy) {
        var pinnedMapType = ParameterizedTypeName.get(MAP, ClassName.get(Object.class), pinnedConnection);

        var runtimeField = FieldSpec.builder(runtime, "runtime", Modifier.PRIVATE, Modifier.FINAL).build();
        var claimsField = FieldSpec.builder(String.class, "claims", Modifier.PRIVATE, Modifier.FINAL).build();
        var policyField = FieldSpec.builder(commitPolicy, "commitPolicy", Modifier.PRIVATE, Modifier.FINAL).build();
        var pinnedField = FieldSpec.builder(pinnedMapType, "pinnedByTenant", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("new $T<>()", LINKED_HASH_MAP)
            .build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(runtime, "runtime")
            .addParameter(String.class, "claims")
            .addParameter(commitPolicy, "commitPolicy")
            .addStatement("this.runtime = runtime")
            .addStatement("this.claims = claims")
            .addStatement("this.commitPolicy = commitPolicy")
            .addJavadoc("Builds a per-operation carrier over {@code runtime} for one request's {@code claims}\n"
                + "and commit policy. One instance per operation; not thread-safe (a single operation's\n"
                + "fetchers run serially on the dispatch thread).\n")
            .build();

        var dslFor = MethodSpec.methodBuilder("dslFor")
            .addModifiers(Modifier.PUBLIC)
            .returns(DSL_CONTEXT)
            .addParameter(Object.class, "tenantKey")
            .addException(SQL_EXCEPTION)
            .addStatement("$T pinned = pinnedByTenant.get(tenantKey)", pinnedConnection)
            .beginControlFlow("if (pinned == null)")
            .addComment("First use of this key in the operation: pin one connection and mount identity on it.")
            .addStatement("pinned = runtime.acquireForTenant(tenantKey, claims)")
            .addStatement("pinnedByTenant.put(tenantKey, pinned)")
            .endControlFlow()
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

        var releaseAll = MethodSpec.methodBuilder("releaseAll")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addStatement("$T failure = null", RuntimeException.class)
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
            .addStatement("pinnedByTenant.clear()")
            .beginControlFlow("if (failure != null)")
            .addStatement("throw failure")
            .endControlFlow()
            .addJavadoc("Releases every pinned connection on every completion path (success, error,\n"
                + "cancellation): each {@code release()} unmounts identity and returns or evicts its own\n"
                + "connection, and one tenant's disconnect failure does not orphan the others. Idempotent:\n"
                + "the map is cleared, so a redundant call is a no-op. Rethrows the first release failure after\n"
                + "attempting them all.\n")
            .build();

        return TypeSpec.classBuilder(TENANT_CONNECTIONS_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Per-operation carrier of the tenant-keyed pinned connections for one request. See\n"
                + "{@code ConnectionRuntimeClassGenerator} for the full contract.\n")
            .addField(runtimeField)
            .addField(claimsField)
            .addField(policyField)
            .addField(pinnedField)
            .addMethod(constructor)
            .addMethod(dslFor)
            .addMethod(releaseAll)
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
