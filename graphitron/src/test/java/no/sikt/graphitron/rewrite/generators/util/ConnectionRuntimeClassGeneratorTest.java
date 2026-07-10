package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier behavioural coverage of {@link ConnectionRuntimeClassGenerator}. Because the runtime is
 * emitted (never shipped as a graphitron artifact), the only honest way to assert acquire/connect/
 * disconnect/release <em>ordering</em> "over a fake {@code DataSource}" is to compile the real
 * emitted {@code TypeSpec}s and drive them: {@link EmittedCodeHarness} compiles them once, and every
 * assertion reads the call log recorded by the fake {@code DataSource}/{@code Connection}/
 * {@code SessionHook} proxies, never the emitted source text.
 */
@UnitTier
class ConnectionRuntimeClassGeneratorTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> pinnedConnectionClass;
    private static Class<?> sessionHookClass;
    private static Class<?> runtimeClass;

    /** Ordered log of observable lifecycle events, shared by all fakes in one test. */
    private final List<String> events = new ArrayList<>();

    @BeforeAll
    static void compileEmittedRuntime() {
        // Since slice 2 the runtime's newGraphQL(schema) references the connection instrumentation,
        // which in turn references the transaction provider: the R429 emitted classes are one connected
        // cluster, so they compile together. This test still only drives the slice-1 classes below.
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compile(units);
        pinnedConnectionClass = harness.load(SCHEMA_PACKAGE + ".PinnedConnection");
        sessionHookClass = harness.load(SCHEMA_PACKAGE + ".SessionHook");
        runtimeClass = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
    }

    @AfterAll
    static void close() {
        if (harness != null) {
            harness.close();
        }
    }

    @BeforeEach
    void resetEvents() {
        events.clear();
    }

    @Test
    void acquireThenRelease_success_mountsThenUnmountsInOrder() throws Throwable {
        DataSource ds = fakeDataSource();
        Object hook = fakeHook("H1", null, null);

        Object pinned = acquire(ds, hook, "claims-payload");
        release(pinned);

        assertThat(events).containsExactly("getConnection", "setAutoCommit:true", "connect", "disconnect:H1", "close");
    }

    @Test
    void connectFailure_evictsAndFailsClosedBeforeAnySql() {
        DataSource ds = fakeDataSource();
        Object hook = fakeHook(null, new SQLException("unentitled role"), null);

        assertThatThrownBy(() -> acquire(ds, hook, "claims-payload"))
            .as("a throwing connect hook rejects the request")
            .isInstanceOf(Throwable.class);

        // Connection acquired, connect attempted and failed, connection evicted (aborted) rather than
        // returned; disconnect never runs and no connection is handed back for SQL.
        assertThat(events).containsExactly("getConnection", "setAutoCommit:true", "connect", "abort");
    }

    @Test
    void disconnectFailure_evictsInsteadOfReturningToPool() throws Throwable {
        DataSource ds = fakeDataSource();
        Object hook = fakeHook("H1", null, new SQLException("unmount failed"));

        Object pinned = acquire(ds, hook, "claims-payload");
        assertThatThrownBy(() -> release(pinned))
            .as("a throwing disconnect hook surfaces the failure")
            .isInstanceOf(Throwable.class);

        // Disconnect attempted and failed, so the physical connection is aborted, never close()d back
        // into the pool.
        assertThat(events).containsExactly("getConnection", "setAutoCommit:true", "connect", "disconnect:H1", "abort");
    }

    @Test
    void release_isIdempotent_cancelThenCompleteUnmountsExactlyOnce() throws Throwable {
        DataSource ds = fakeDataSource();
        Object hook = fakeHook("H1", null, null);

        Object pinned = acquire(ds, hook, "claims-payload");
        release(pinned); // e.g. cancellation signal
        release(pinned); // e.g. completion signal

        assertThat(events).containsExactly("getConnection", "setAutoCommit:true", "connect", "disconnect:H1", "close");
    }

    @Test
    void release_isOutcomeAgnostic_disconnectFiresOnEveryCompletionPath() throws Throwable {
        // Success path: the operation completed and the caller releases.
        List<String> success = driveRelease(() -> { /* normal completion */ });
        assertThat(success).containsExactly("getConnection", "setAutoCommit:true", "connect", "disconnect:H1", "close");

        // Error path: the operation threw; the caller still releases in its finally.
        List<String> error = driveRelease(() -> { throw new RuntimeException("operation failed"); });
        assertThat(error).containsExactly("getConnection", "setAutoCommit:true", "connect", "disconnect:H1", "close");
    }

    @Test
    void handleThreading_nullHandle_disconnectReceivesNull() throws Throwable {
        DataSource ds = fakeDataSource();
        Object hook = fakeHook(null, null, null); // connect returns no handle

        Object pinned = acquire(ds, hook, "claims-payload");
        release(pinned);

        assertThat(events).containsExactly("getConnection", "setAutoCommit:true", "connect", "disconnect:null", "close");
    }

    @Test
    void runtimeDefaultPath_mountsNoIdentityAndReturnsConnection() throws Throwable {
        DataSource ds = fakeDataSource();
        Object runtime = runtimeClass.getConstructor(DataSource.class, SQLDialect.class)
            .newInstance(ds, SQLDialect.POSTGRES);

        Object pinned = runtimeClass.getMethod("acquire", String.class).invoke(runtime, "claims-payload");
        release(pinned);

        // No <sessionState> configured -> SessionHook.NONE: the connection is opened and returned to the
        // pool, and no identity is ever mounted (no connect/disconnect touches our recording hook).
        assertThat(events).containsExactly("getConnection", "setAutoCommit:true", "close");
    }

    // ===== hooks run outside any transaction (structural guarantee) =====

    @Test
    void acquire_normalizesAutoCommit_beforeConnectHookRuns() throws Throwable {
        // The fake DataSource hands out connections with autocommit=false (a pool can be configured
        // that way); acquire must normalize before connect, or on Postgres the mount's set_config
        // would sit in an implicit never-committed transaction and revert with a later rollback.
        DataSource ds = fakeDataSource();
        Object hook = fakeHook("H1", null, null);

        acquire(ds, hook, "claims-payload");

        assertThat(events)
            .as("autocommit is normalized after pinning and before the connect hook")
            .startsWith("getConnection", "setAutoCommit:true", "connect");
    }

    @Test
    void release_operationLeftTransactionOpen_settlesBeforeDisconnectHookRuns() throws Throwable {
        DataSource ds = fakeDataSource();
        Object hook = fakeHook("H1", null, null);

        Object pinned = acquire(ds, hook, "claims-payload");
        // Simulate an operation that died mid-mutation: the provider turned autocommit off and never
        // settled. Release must roll back and restore autocommit before the disconnect hook, so the
        // hook's clears commit immediately instead of being reverted by the pool's return-rollback.
        Connection connection = (Connection) pinnedConnectionClass.getMethod("connection").invoke(pinned);
        connection.setAutoCommit(false);
        release(pinned);

        assertThat(events).containsExactly(
            "getConnection", "setAutoCommit:true", "connect",
            "setAutoCommit:false",                       // the simulated abandoned mutation transaction
            "rollback", "setAutoCommit:true",            // release settles before unmounting
            "disconnect:H1", "close");
    }

    // --- driving helpers -------------------------------------------------------------------------

    /** Runs acquire, then {@code body}, then release in a finally, capturing the event log. */
    private List<String> driveRelease(Runnable body) throws Throwable {
        List<String> log = new ArrayList<>();
        DataSource ds = fakeDataSource(log);
        Object hook = fakeHook(log, "H1", null, null);
        Object pinned = acquire(ds, hook, "claims-payload");
        try {
            body.run();
        } catch (RuntimeException ignored) {
            // the operation failed; release still must unmount
        } finally {
            release(pinned);
        }
        return log;
    }

    private Object acquire(DataSource dataSource, Object hook, String claims) throws Throwable {
        Executor sameThread = Runnable::run;
        Method acquire = pinnedConnectionClass.getMethod(
            "acquire", DataSource.class, sessionHookClass, String.class, Executor.class);
        try {
            return acquire.invoke(null, dataSource, hook, claims, sameThread);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void release(Object pinned) throws Throwable {
        try {
            pinnedConnectionClass.getMethod("release").invoke(pinned);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // --- recording fakes -------------------------------------------------------------------------

    private DataSource fakeDataSource() {
        return fakeDataSource(events);
    }

    private DataSource fakeDataSource(List<String> log) {
        Connection connection = fakeConnection(log);
        return (DataSource) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    log.add("getConnection");
                    return connection;
                }
                return objectMethodOrDefault(proxy, method, args, "fakeDataSource");
            });
    }

    private Connection fakeConnection(List<String> log) {
        // Starts autocommit=false: pools (Agroal, Hikari) can be configured to hand out connections
        // that way, and the lifecycle must normalize rather than trust the pool's configuration.
        boolean[] autoCommit = {false};
        return (Connection) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "close" -> { log.add("close"); yield null; }
                case "abort" -> { log.add("abort"); yield null; }
                case "setAutoCommit" -> { autoCommit[0] = (Boolean) args[0]; log.add("setAutoCommit:" + args[0]); yield null; }
                case "getAutoCommit" -> autoCommit[0];
                case "rollback" -> { log.add("rollback"); yield null; }
                default -> objectMethodOrDefault(proxy, method, args, "fakeConnection");
            });
    }

    private Object fakeHook(String handle, Throwable connectThrows, Throwable disconnectThrows) {
        return fakeHook(events, handle, connectThrows, disconnectThrows);
    }

    private Object fakeHook(List<String> log, String handle, Throwable connectThrows, Throwable disconnectThrows) {
        return Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{sessionHookClass}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "connect" -> {
                        log.add("connect");
                        if (connectThrows != null) {
                            throw connectThrows;
                        }
                        return handle;
                    }
                    case "disconnect" -> {
                        log.add("disconnect:" + args[1]);
                        if (disconnectThrows != null) {
                            throw disconnectThrows;
                        }
                        return null;
                    }
                    default -> {
                        return objectMethodOrDefault(proxy, method, args, "fakeHook");
                    }
                }
            });
    }

    /** Handles Object methods on a proxy and returns type-appropriate defaults for everything else. */
    private static Object objectMethodOrDefault(Object proxy, Method method, Object[] args, String label) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> label;
            default -> defaultValue(method.getReturnType());
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
