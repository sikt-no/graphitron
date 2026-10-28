package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
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

import javax.lang.model.element.Modifier;
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
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, null, hooks)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        // The instrumentation reports a mutation field's settle failure through the router, which
        // raises the client-error type; a real build always emits both beside the runtime.
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.generate()) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.generate(PACKAGE)) {
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
            .as("a throwing mount becomes the request-scoped rejection, the mount's throw as its cause")
            .satisfies(t -> assertThat(t.getClass().getSimpleName()).isEqualTo("SessionMountException"))
            .hasCause(LifecycleHookFixture.mountThrows);

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

    // --- the fetcher-exception log and response -----------------------------------------------

    @Test
    void fetcherErrorLog_isAFunctionalInterfaceWithNoTenantArmInASingleTenantBuild() {
        TypeSpec log = unit(ConnectionRuntimeClassGenerator.generate(PACKAGE),
            ConnectionRuntimeClassGenerator.FETCHER_ERROR_LOG_CLASS_NAME);

        assertThat(log.kind()).isEqualTo(TypeSpec.Kind.INTERFACE);
        assertThat(log.annotations()).extracting(a -> a.type().toString())
            .containsExactly(FunctionalInterface.class.getName());
        assertThat(abstractMethodNames(log))
            .as("exactly one abstract method, so a later hook point can arrive as a default method")
            .containsExactly("unmatched");
        assertThat(log.methodSpecs()).extracting(MethodSpec::name).doesNotContain("tenantFailed");
        assertThat(parameterTypes(method(log, "unmatched"))).containsExactly(
            "java.lang.Throwable", "java.lang.String", "graphql.schema.DataFetchingEnvironment");
        MethodSpec matched = method(log, "matched");
        assertThat(matched.modifiers()).contains(Modifier.DEFAULT);
        assertThat(parameterTypes(matched)).containsExactly(
            "java.lang.Throwable", "java.lang.Throwable", "java.lang.String", "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void fetcherErrorResponse_hasFourDefaultMethodsAndNoAbstractOne() {
        TypeSpec response = unit(ConnectionRuntimeClassGenerator.generate(PACKAGE),
            ConnectionRuntimeClassGenerator.FETCHER_ERROR_RESPONSE_CLASS_NAME);

        assertThat(response.kind()).isEqualTo(TypeSpec.Kind.INTERFACE);
        assertThat(response.annotations()).as("not a functional interface: a consumer overrides what it needs")
            .isEmpty();
        assertThat(abstractMethodNames(response)).isEmpty();
        assertThat(response.methodSpecs()).extracting(MethodSpec::name)
            .containsExactly("reference", "matchedMessage", "clientErrorMessage", "unmatchedMessage");
        assertThat(response.methodSpecs()).allSatisfy(m -> {
            assertThat(m.modifiers()).contains(Modifier.DEFAULT);
            assertThat(m.returnType().toString()).isEqualTo("java.lang.String");
        });
        assertThat(parameterTypes(method(response, "reference"))).containsExactly(
            "java.lang.Throwable", "graphql.schema.DataFetchingEnvironment");
        assertThat(parameterTypes(method(response, "matchedMessage"))).containsExactly(
            "java.lang.Throwable", "java.lang.String", "java.lang.String", "graphql.schema.DataFetchingEnvironment");
        assertThat(parameterTypes(method(response, "clientErrorMessage"))).containsExactly(
            SCHEMA_PACKAGE + ".GraphitronClientException", "graphql.schema.DataFetchingEnvironment");
        assertThat(parameterTypes(method(response, "unmatchedMessage"))).containsExactly(
            "java.lang.Throwable", "java.lang.String", "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void fetcherErrorLog_tenantFailedIsADefaultTypedWithTheTenantKey() {
        TypeSpec log = unit(
            ConnectionRuntimeClassGenerator.generate(PACKAGE, SessionHooks.NotConfigured.INSTANCE,
                ClassName.get(Integer.class), TenantColumnFixtures.INTEGER_KEY_COLUMN),
            ConnectionRuntimeClassGenerator.FETCHER_ERROR_LOG_CLASS_NAME);

        assertThat(abstractMethodNames(log)).containsExactly("unmatched");
        MethodSpec tenantFailed = method(log, "tenantFailed");
        assertThat(tenantFailed.modifiers()).contains(Modifier.DEFAULT);
        assertThat(parameterTypes(tenantFailed)).containsExactly(
            "java.lang.Integer", "java.lang.Throwable", "java.lang.String", "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void runtime_carriesTheLogThroughAWitherAndAnOptionalAccessor() {
        TypeSpec runtime = unit(ConnectionRuntimeClassGenerator.generate(PACKAGE),
            ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        var logType = ClassName.get(SCHEMA_PACKAGE, ConnectionRuntimeClassGenerator.FETCHER_ERROR_LOG_CLASS_NAME);

        MethodSpec wither = method(runtime, "withFetcherErrorLog");
        assertThat(wither.returnType()).isEqualTo(ClassName.get(SCHEMA_PACKAGE, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME));
        assertThat(wither.parameters()).extracting(ParameterSpec::type).containsExactly(logType);
        assertThat(method(runtime, "fetcherErrorLog").returnType())
            .isEqualTo(ParameterizedTypeName.get(ClassName.get(Optional.class), logType));

        var responseType = ClassName.get(SCHEMA_PACKAGE, ConnectionRuntimeClassGenerator.FETCHER_ERROR_RESPONSE_CLASS_NAME);
        assertThat(method(runtime, "withFetcherErrorResponse").parameters()).extracting(ParameterSpec::type)
            .containsExactly(responseType);
        assertThat(method(runtime, "fetcherErrorResponse").returnType())
            .isEqualTo(ParameterizedTypeName.get(ClassName.get(Optional.class), responseType));
    }

    @Test
    void withFetcherErrorResponse_keepsTheLog_andWithFetcherErrorLogKeepsTheResponse() throws Throwable {
        Class<?> logClass = harness.load(SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.FETCHER_ERROR_LOG_CLASS_NAME);
        Class<?> responseClass = harness.load(
            SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.FETCHER_ERROR_RESPONSE_CLASS_NAME);
        Object log = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{logClass},
            (proxy, method, args) -> objectMethodOrDefault(proxy, method, args, "recordingLog"));
        Object response = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{responseClass},
            (proxy, method, args) -> objectMethodOrDefault(proxy, method, args, "recordingResponse"));
        Object original = runtimeClass.getConstructor(DataSource.class, SQLDialect.class)
            .newInstance(fakeDataSource(), SQLDialect.POSTGRES);

        Object logged = runtimeClass.getMethod("withFetcherErrorLog", logClass).invoke(original, log);
        Object both = runtimeClass.getMethod("withFetcherErrorResponse", responseClass).invoke(logged, response);
        Object relogged = runtimeClass.getMethod("withFetcherErrorLog", logClass).invoke(both, log);

        assertThat(runtimeClass.getMethod("fetcherErrorLog").invoke(both)).isEqualTo(Optional.of(log));
        assertThat(runtimeClass.getMethod("fetcherErrorResponse").invoke(both)).isEqualTo(Optional.of(response));
        assertThat(runtimeClass.getMethod("fetcherErrorResponse").invoke(relogged)).isEqualTo(Optional.of(response));
        assertThat(runtimeClass.getMethod("fetcherErrorResponse").invoke(logged)).isEqualTo(Optional.empty());
        assertThatThrownBy(() -> {
            try {
                runtimeClass.getMethod("withFetcherErrorResponse", responseClass).invoke(original, (Object) null);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void withFetcherErrorLog_returnsACopyCarryingTheLog_andLeavesTheOriginalWithout() throws Throwable {
        Class<?> logClass = harness.load(SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.FETCHER_ERROR_LOG_CLASS_NAME);
        Object log = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{logClass},
            (proxy, method, args) -> objectMethodOrDefault(proxy, method, args, "recordingLog"));
        Object original = runtimeClass.getConstructor(DataSource.class, SQLDialect.class)
            .newInstance(fakeDataSource(), SQLDialect.POSTGRES);

        Object copy = runtimeClass.getMethod("withFetcherErrorLog", logClass).invoke(original, log);

        assertThat(runtimeClass.getMethod("fetcherErrorLog").invoke(copy)).isEqualTo(Optional.of(log));
        assertThat(runtimeClass.getMethod("fetcherErrorLog").invoke(original)).isEqualTo(Optional.empty());
        assertThat(runtimeClass.getMethod("dialect").invoke(copy)).isEqualTo(SQLDialect.POSTGRES);
        assertThatThrownBy(() -> {
            try {
                runtimeClass.getMethod("withFetcherErrorLog", logClass).invoke(original, (Object) null);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(NullPointerException.class);
    }

    private static TypeSpec unit(List<TypeSpec> units, String name) {
        return units.stream().filter(u -> u.name().equals(name)).findFirst().orElseThrow();
    }

    private static MethodSpec method(TypeSpec type, String name) {
        return type.methodSpecs().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> abstractMethodNames(TypeSpec type) {
        return type.methodSpecs().stream()
            .filter(m -> m.modifiers().contains(Modifier.ABSTRACT))
            .map(MethodSpec::name)
            .toList();
    }

    private static List<String> parameterTypes(MethodSpec method) {
        return method.parameters().stream().map(p -> p.type().toString()).toList();
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
