package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * R429 slice 1 — emits the connection-lifecycle runtime substrate into the consumer's
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
 * transaction concept: commit-vs-rollback and read-only-vs-writable are the orthogonal transaction
 * axis that R429 slice 2's acquisition handles + {@code TransactionProvider} layer over this seam.
 * The connect OUT value is the only thing called a "handle" here.
 *
 * <h2>The lifecycle contract (unit-pinned in {@code ConnectionRuntimeClassGeneratorTest})</h2>
 * <ul>
 *   <li><b>Acquire.</b> {@code DataSource.getConnection()}, then the connect hook with the opaque
 *       claims, capturing its OUT handle. <b>Fail closed:</b> if connect throws (it may have
 *       partially mounted session state first), the connection is evicted, never returned, and the
 *       failure propagates before any operation SQL runs.</li>
 *   <li><b>Release.</b> The disconnect hook fires on <em>every</em> completion path (success, error,
 *       cancellation) bound to the captured handle; release is idempotent. <b>Evict on unmount
 *       failure:</b> if disconnect throws or cannot run, the physical connection is aborted and
 *       never returned to the pool, so a connection whose identity cannot be proven unmounted gets
 *       no next borrower.</li>
 * </ul>
 * Eviction uses {@link java.sql.Connection#abort(java.util.concurrent.Executor)} (JDBC, valid Java
 * 17): pool wrappers (Agroal/HikariCP) honour it as a true physical evict where {@code close()}
 * merely reclaims the connection to the pool. The runtime supplies a same-thread executor.
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
 * Slice 1 wires {@code SessionHook.NONE} (a no-op null-object: mounts and unmounts nothing) so the
 * no-{@code <sessionState>} path never branches on a nullable hook. Slice 3 regenerates the runtime
 * to bake the configured connect/disconnect callables (Postgres {@code <variables>} sugar or the
 * function-hook form) into the {@code sessionHook} field; that change is additive to this surface.
 */
public final class ConnectionRuntimeClassGenerator {

    public static final String RUNTIME_CLASS_NAME = "GraphitronRuntime";
    public static final String PINNED_CONNECTION_CLASS_NAME = "PinnedConnection";
    public static final String SESSION_HOOK_CLASS_NAME = "SessionHook";

    private static final ClassName CONNECTION = ClassName.get("java.sql", "Connection");
    private static final ClassName SQL_EXCEPTION = ClassName.get("java.sql", "SQLException");
    private static final ClassName DATA_SOURCE = ClassName.get("javax.sql", "DataSource");
    private static final ClassName EXECUTOR = ClassName.get("java.util.concurrent", "Executor");
    private static final ClassName SQL_DIALECT = ClassName.get("org.jooq", "SQLDialect");
    private static final ClassName OBJECTS = ClassName.get("java.util", "Objects");

    private ConnectionRuntimeClassGenerator() {}

    /**
     * @param outputPackage the consumer's root output package; the three classes are emitted into
     *                      {@code outputPackage + ".schema"} (beside {@code GraphitronContext})
     */
    public static List<TypeSpec> generate(String outputPackage) {
        String schemaPackage = outputPackage + ".schema";
        var sessionHook = ClassName.get(schemaPackage, SESSION_HOOK_CLASS_NAME);
        var pinnedConnection = ClassName.get(schemaPackage, PINNED_CONNECTION_CLASS_NAME);
        return List.of(
            sessionHook(sessionHook),
            pinnedConnection(pinnedConnection, sessionHook),
            runtime(sessionHook, pinnedConnection));
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
                + "@param connection the pinned connection, before any operation SQL\n"
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
                + "@param connection the still-pinned connection\n"
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
                + "language (RAS/VPD on Oracle, {@code set_config} on Postgres); graphitron only guarantees\n"
                + "the pair runs at mount and unmount. Slice 3 generates the concrete implementation from\n"
                + "{@code <sessionState>} configuration; {@link #NONE} is the no-op default.\n")
            .addMethod(connect)
            .addMethod(disconnect)
            .addField(none)
            .build();
    }

    /** The acquisition-scoped pinned connection: acquire/release lifecycle with fail-closed + evict. */
    private static TypeSpec pinnedConnection(ClassName pinnedConnection, ClassName sessionHook) {
        var connectionField = FieldSpec.builder(CONNECTION, "connection", Modifier.PRIVATE, Modifier.FINAL).build();
        var hookField = FieldSpec.builder(sessionHook, "hook", Modifier.PRIVATE, Modifier.FINAL).build();
        var handleField = FieldSpec.builder(String.class, "handle", Modifier.PRIVATE, Modifier.FINAL).build();
        var abortExecutorField = FieldSpec.builder(EXECUTOR, "abortExecutor", Modifier.PRIVATE, Modifier.FINAL).build();
        var releasedField = FieldSpec.builder(boolean.class, "released", Modifier.PRIVATE).build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .addParameter(CONNECTION, "connection")
            .addParameter(sessionHook, "hook")
            .addParameter(String.class, "handle")
            .addParameter(EXECUTOR, "abortExecutor")
            .addStatement("this.connection = connection")
            .addStatement("this.hook = hook")
            .addStatement("this.handle = handle")
            .addStatement("this.abortExecutor = abortExecutor")
            .build();

        var acquire = MethodSpec.methodBuilder("acquire")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(pinnedConnection)
            .addParameter(DATA_SOURCE, "dataSource")
            .addParameter(sessionHook, "hook")
            .addParameter(String.class, "claims")
            .addParameter(EXECUTOR, "abortExecutor")
            .addException(SQL_EXCEPTION)
            .addStatement("$T connection = dataSource.getConnection()", CONNECTION)
            .addStatement("String handle")
            .beginControlFlow("try")
            .addStatement("handle = hook.connect(connection, claims)")
            .nextControlFlow("catch ($T connectFailure)", Throwable.class)
            .addComment("Fail closed: connect may have partially mounted session state before throwing.")
            .addComment("Evict rather than return a half-mounted connection to the pool; reject before any SQL.")
            .addStatement("evict(connection, abortExecutor)")
            .addStatement("throw rethrow(connectFailure)")
            .endControlFlow()
            .addStatement("return new $T(connection, hook, handle, abortExecutor)", pinnedConnection)
            .addJavadoc("Pins one connection and mounts identity on it. Fail-closed: a throwing connect\n"
                + "hook evicts the connection and propagates before any operation SQL runs.\n")
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
            .addStatement("hook.disconnect(connection, handle)")
            .nextControlFlow("catch ($T disconnectFailure)", Throwable.class)
            .addComment("Identity cannot be proven unmounted: evict the physical connection, never return it.")
            .addStatement("evict(connection, abortExecutor)")
            .addStatement("throw rethrow(disconnectFailure)")
            .endControlFlow()
            .addStatement("closeReturningToPool(connection)")
            .addJavadoc("Unmounts identity and releases the connection. Fires disconnect on every completion\n"
                + "path (success, error, cancellation); idempotent, so a redundant cancel-then-complete\n"
                + "release unmounts exactly once. Evicts on disconnect failure.\n")
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
                + "lifetime. Carries no transaction concept (R429 slice 2's read-only/writable acquisition\n"
                + "handles + {@code TransactionProvider} compose over this seam). See\n"
                + "{@code ConnectionRuntimeClassGenerator} for the full lifecycle contract.\n")
            .addField(connectionField)
            .addField(hookField)
            .addField(handleField)
            .addField(abortExecutorField)
            .addField(releasedField)
            .addMethod(constructor)
            .addMethod(acquire)
            .addMethod(connectionAccessor)
            .addMethod(release)
            .addMethod(close)
            .addMethod(evict)
            .addMethod(closeReturningToPool)
            .addMethod(rethrow)
            .build();
    }

    /** The application-scoped runtime holding the DataSource, dialect, and baked session hook. */
    private static TypeSpec runtime(ClassName sessionHook, ClassName pinnedConnection) {
        var dataSourceField = FieldSpec.builder(DATA_SOURCE, "dataSource", Modifier.PRIVATE, Modifier.FINAL).build();
        var dialectField = FieldSpec.builder(SQL_DIALECT, "dialect", Modifier.PRIVATE, Modifier.FINAL).build();
        var hookField = FieldSpec.builder(sessionHook, "sessionHook", Modifier.PRIVATE, Modifier.FINAL).build();
        // Same-thread executor for Connection.abort(); the abort work is trivial and must complete before
        // the borrow returns, so there is no reason to hand it to another thread.
        var abortExecutorField = FieldSpec.builder(EXECUTOR, "abortExecutor", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("$T::run", Runnable.class)
            .build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(DATA_SOURCE, "dataSource")
            .addParameter(SQL_DIALECT, "dialect")
            .addStatement("this.dataSource = $T.requireNonNull(dataSource, $S)", OBJECTS, "dataSource")
            .addStatement("this.dialect = $T.requireNonNull(dialect, $S)", OBJECTS, "dialect")
            .addComment("Slice 3 replaces NONE with the connect/disconnect hook baked from <sessionState>.")
            .addStatement("this.sessionHook = $T.NONE", sessionHook)
            .addJavadoc("Builds the application-scoped runtime over a consumer-owned {@code DataSource} and\n"
                + "dialect. The consumer (or their framework) still owns pool creation and tuning; the\n"
                + "runtime owns acquisition, transactions, and identity on top of it.\n"
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
            .addStatement("return $T.acquire(dataSource, sessionHook, claims, abortExecutor)", pinnedConnection)
            .addJavadoc("Pins one connection from the {@code DataSource} and mounts identity on it from the\n"
                + "opaque {@code claims} payload. Fail-closed. The caller releases the returned\n"
                + "{@code PinnedConnection} exactly once at operation completion; R429 slice 2 wires this\n"
                + "into graphql-java execution instrumentation so consumers register nothing.\n"
                + "@param claims the opaque per-request claims payload (typically the JWT), never parsed here\n")
            .build();

        return TypeSpec.classBuilder(RUNTIME_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Application-scoped runtime that owns the connection lifecycle: built once at wiring\n"
                + "time via {@code Graphitron.runtime(dataSource, dialect)}, it pins one connection per\n"
                + "operation, mounts and unmounts per-request identity through the {@link $T} seam, and (in\n"
                + "slice 2) demarcates operation-typed transactions. Holds no per-request state.\n", sessionHook)
            .addField(dataSourceField)
            .addField(dialectField)
            .addField(hookField)
            .addField(abortExecutorField)
            .addMethod(constructor)
            .addMethod(dialectAccessor)
            .addMethod(acquire)
            .build();
    }
}
