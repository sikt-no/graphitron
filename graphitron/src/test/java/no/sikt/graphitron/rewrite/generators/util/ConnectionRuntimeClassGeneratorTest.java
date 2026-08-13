package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.session.SessionHooks;
import no.sikt.graphitron.rewrite.session.SessionHooksFixtures;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier behavioural coverage of {@link ConnectionRuntimeClassGenerator}'s lifecycle contract.
 * Because the runtime is emitted (never shipped as a graphitron artifact), the only honest way to
 * assert acquire/mount/unmount/release <em>ordering</em> over a fake {@code DataSource} is to
 * compile the real emitted {@code TypeSpec}s and drive them: {@link EmittedCodeHarness} compiles
 * them once, and every assertion reads the interleaved call log recorded by the fake
 * {@code DataSource}/{@code Connection} proxies and the {@link LifecycleHookFixture} the emitted
 * hook class calls directly, never the emitted source text.
 *
 * <p>The autocommit facts asserted here carry the deleted re-fire's guarantee: graphitron asserts
 * the transaction mode on a connection it owns (before the mount, so the mount is its own
 * committed transaction; at release before the unmount, rolling back first because switching
 * autocommit on commits an open transaction). No {@code afterSettle} and no re-fire exist.
 */
@UnitTier
class ConnectionRuntimeClassGeneratorTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";
    private static final String FIXTURE = LifecycleHookFixture.class.getName();

    private static EmittedCodeHarness harness;
    private static Class<?> pinnedConnectionClass;
    private static Class<?> runtimeClass;

    /** Ordered log of observable lifecycle events, shared by all fakes and the hook fixture. */
    private final List<String> events = new ArrayList<>();

    /** The fixture hook pair: {@code Connection}-seam mount with a String payload, String handle. */
    private static SessionHooks fixtureHooks() {
        var mount = SessionHooksFixtures.hookRef(FIXTURE, "mount", ClassName.get(String.class),
            SessionHooksFixtures.connectionSeam(), SessionHooksFixtures.stringPayload("claims"));
        var unmount = SessionHooksFixtures.hookRef(FIXTURE, "unmount", TypeName.VOID,
            SessionHooksFixtures.connectionSeam(),
            SessionHooksFixtures.handleParam(ClassName.get(String.class)));
        return new SessionHooks.Handled(mount, ClassName.get(String.class), Optional.of(unmount));
    }

    @BeforeAll
    static void compileEmittedRuntime() {
        // The runtime's newGraphQL(schema) references the connection instrumentation, which in
        // turn references the transaction provider and the carrier: the emitted runtime classes
        // are one connected cluster, so they compile together. This test still only drives the
        // connection-lifecycle classes below.
        harness = compile(fixtureHooks());
        pinnedConnectionClass = harness.load(SCHEMA_PACKAGE + ".PinnedConnection");
        runtimeClass = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
    }

    private static EmittedCodeHarness compile(SessionHooks hooks) {
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, false, hooks)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        return EmittedCodeHarness.compile(units);
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
        LifecycleHookFixture.reset(events);
    }

    @Test
    void acquireThenRelease_success_mountsThenUnmountsInOrder() throws Throwable {
        Object pinned = acquire(fakeDataSource(), "claims-payload");
        release(pinned);

        assertThat(events).containsExactly(
            "getConnection", "setAutoCommit:true", "connect->H1", "disconnect:H1", "close");
    }

    @Test
    void mountFailure_evictsAndFailsClosedBeforeAnySql() {
        LifecycleHookFixture.mountThrows = new RuntimeException("unentitled role");

        assertThatThrownBy(() -> acquire(fakeDataSource(), "claims-payload"))
            .as("a throwing mount rejects the request")
            .isInstanceOf(Throwable.class);

        // Connection acquired, mount attempted and failed, connection evicted (aborted) rather
        // than returned; unmount never runs and no connection is handed back for SQL.
        assertThat(events).containsExactly("getConnection", "setAutoCommit:true", "connect->H1", "abort");
    }

    @Test
    void unmountFailure_evictsInsteadOfReturningToPool() throws Throwable {
        LifecycleHookFixture.unmountThrows = new RuntimeException("unmount failed");

        Object pinned = acquire(fakeDataSource(), "claims-payload");
        assertThatThrownBy(() -> release(pinned))
            .as("a throwing unmount surfaces the failure")
            .isInstanceOf(Throwable.class);

        // Unmount attempted and failed, so the physical connection is aborted, never close()d
        // back into the pool.
        assertThat(events).containsExactly(
            "getConnection", "setAutoCommit:true", "connect->H1", "disconnect:H1", "abort");
    }

    @Test
    void release_isIdempotent_cancelThenCompleteUnmountsExactlyOnce() throws Throwable {
        Object pinned = acquire(fakeDataSource(), "claims-payload");
        release(pinned); // e.g. cancellation signal
        release(pinned); // e.g. completion signal

        assertThat(events).containsExactly(
            "getConnection", "setAutoCommit:true", "connect->H1", "disconnect:H1", "close");
    }

    @Test
    void release_isOutcomeAgnostic_unmountFiresOnEveryCompletionPath() throws Throwable {
        // Success path: the operation completed and the caller releases.
        assertThat(driveRelease(() -> { /* normal completion */ })).containsExactly(
            "getConnection", "setAutoCommit:true", "connect->H1", "disconnect:H1", "close");

        // Error path: the operation threw; the caller still releases in its finally.
        resetEvents();
        assertThat(driveRelease(() -> { throw new RuntimeException("operation failed"); }))
            .containsExactly(
                "getConnection", "setAutoCommit:true", "connect->H1", "disconnect:H1", "close");
    }

    @Test
    void notConfigured_acquireAssertsAutocommitAndMountsNothing() throws Throwable {
        // The NotConfigured arm emits no hook class and no mount call at all, which is stronger
        // than holding a no-op; the autocommit assertion stays, as the resting state graphitron
        // holds an owned connection in.
        try (var plain = compile(SessionHooks.NotConfigured.INSTANCE)) {
            Class<?> plainRuntime = plain.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
            Object runtime = plainRuntime.getConstructor(DataSource.class, SQLDialect.class)
                .newInstance(fakeDataSource(), SQLDialect.POSTGRES);
            Object pinned = plainRuntime.getMethod("acquire").invoke(runtime);
            plain.load(SCHEMA_PACKAGE + ".PinnedConnection")
                .getMethod("release").invoke(pinned);

            assertThat(events).containsExactly("getConnection", "setAutoCommit:true", "close");
        }
    }

    // ===== graphitron asserts the connection's transaction mode =====

    @Test
    void acquire_assertsAutocommit_beforeTheMountRuns() throws Throwable {
        // The fake DataSource hands out connections with autocommit=false (a pool can be
        // configured that way); acquire must assert the mode before the mount, or on Postgres
        // the mount's session-scoped state would sit in an implicit never-committed transaction
        // and revert with a later rollback, leaving the operation running unmounted under RLS.
        acquire(fakeDataSource(), "claims-payload");

        assertThat(events)
            .as("autocommit is asserted after pinning and before the mount")
            .startsWith("getConnection", "setAutoCommit:true", "connect->H1");
    }

    @Test
    void release_operationLeftTransactionOpen_settlesBeforeTheUnmountRuns() throws Throwable {
        Object pinned = acquire(fakeDataSource(), "claims-payload");
        // Simulate an operation that died mid-mutation: the provider turned autocommit off and
        // never settled. Release must roll back and assert autocommit before the unmount (in
        // that order: switching autocommit on commits an open transaction), so the unmount's
        // clears commit immediately instead of being reverted by the pool's return-rollback.
        Connection connection = (Connection) pinnedConnectionClass.getMethod("connection").invoke(pinned);
        connection.setAutoCommit(false);
        release(pinned);

        assertThat(events).containsExactly(
            "getConnection", "setAutoCommit:true", "connect->H1",
            "setAutoCommit:false",                       // the simulated abandoned mutation transaction
            "rollback", "setAutoCommit:true",            // release settles before unmounting
            "disconnect:H1", "close");
    }

    @Test
    void handle_isCarriedFromMountToRelease_andExposedForTheCarrierEntry() throws Throwable {
        Object pinned = acquire(fakeDataSource(), "claims-payload");

        assertThat(pinnedConnectionClass.getMethod("handle").invoke(pinned))
            .as("the mount's returned handle is the pinned carrier's one retained field, "
                + "read by release and published on the carrier entry's Configuration")
            .isEqualTo("H1");
        release(pinned);
        assertThat(events).last().isEqualTo("close");
        assertThat(events).contains("disconnect:H1");
    }

    // --- driving helpers -----------------------------------------------------------------------

    /** Runs acquire, then {@code body}, then release in a finally, capturing the event log. */
    private List<String> driveRelease(Runnable body) throws Throwable {
        Object pinned = acquire(fakeDataSource(), "claims-payload");
        try {
            body.run();
        } catch (RuntimeException ignored) {
            // the operation failed; release still must unmount
        } finally {
            release(pinned);
        }
        return events;
    }

    private Object acquire(DataSource dataSource, String claims) throws Throwable {
        Executor sameThread = Runnable::run;
        Method acquire = pinnedConnectionClass.getMethod(
            "acquire", DataSource.class, SQLDialect.class, Settings.class, Executor.class, String.class);
        try {
            return acquire.invoke(null, dataSource, SQLDialect.POSTGRES, new Settings(), sameThread, claims);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void release(Object pinned) throws Throwable {
        try {
            pinned.getClass().getMethod("release").invoke(pinned);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // --- recording fakes -------------------------------------------------------------------------

    private DataSource fakeDataSource() {
        Connection connection = fakeConnection(events);
        return (DataSource) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    events.add("getConnection");
                    return connection;
                }
                return objectMethodOrDefault(proxy, method, args, "fakeDataSource");
            });
    }

    private Connection fakeConnection(List<String> log) {
        // Starts autocommit=false: pools (Agroal, Hikari) can be configured to hand out connections
        // that way, and the lifecycle asserts the mode rather than trusting the pool's configuration.
        boolean[] autoCommit = {false};
        return (Connection) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "close" -> { log.add("close"); yield null; }
                case "abort" -> { log.add("abort"); yield null; }
                case "setAutoCommit" -> { autoCommit[0] = (Boolean) args[0]; log.add("setAutoCommit:" + args[0]); yield null; }
                case "getAutoCommit" -> autoCommit[0];
                case "commit" -> { log.add("commit"); yield null; }
                case "rollback" -> { log.add("rollback"); yield null; }
                default -> objectMethodOrDefault(proxy, method, args, "fakeConnection");
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
