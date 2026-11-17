package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.session.SessionIdentityFixtures;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.SQLDialect;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier coverage of the runtime's keyed acquisition primitive
 * ({@code GraphitronRuntime.acquire(key, source)}) and the per-operation {@code OwnedConnections}
 * carrier. Because these are emitted, the honest way to assert their behaviour is to compile the real
 * emitted {@code TypeSpec}s and drive them over fake JDBC. The request's source is a
 * {@link RecordingSource}, so the real mount/unmount runs at acquisition/release, letting a fake
 * {@code Connection} observe both phases as the {@code prepareStatement} calls the fixture makes;
 * the fake {@code DataSource}s are keyed so "the right pool per key" and "one connection per distinct
 * key" read straight off the call log.
 */
@UnitTier
class OwnedConnectionsGeneratorTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> runtimeClass;
    private static Class<?> ownedConnectionsClass;
    private static Class<?> commitPolicyClass;
    private static Object commitPolicyCommit;

    private final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void compile() {
        var hooks = SessionIdentityFixtures.recording();
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
        harness = EmittedCodeHarness.compile(units);
        runtimeClass = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
        ownedConnectionsClass = harness.load(SCHEMA_PACKAGE + ".OwnedConnections");
        Class<?> providerClass = harness.load(SCHEMA_PACKAGE + ".GraphitronTransactionProvider");
        commitPolicyClass = java.util.Arrays.stream(providerClass.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("CommitPolicy"))
            .findFirst().orElseThrow();
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object commit = Enum.valueOf((Class) commitPolicyClass, "COMMIT");
        commitPolicyCommit = commit;
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
    void acquire_leasesTheKeysPool_andAKeyTheSourceRefusesRaisesBeforeAnySql() throws Throwable {
        Object source = source(Map.of("A", fakeDataSource("A", false), "B", fakeDataSource("B", false)));

        acquire(newRuntime(), "A", source);
        assertThat(events)
            .as("acquiring for key A leases A's pool and mounts identity on it")
            .containsExactly("getConnection:A", "connect:A");

        events.clear();
        assertThatThrownBy(() -> acquire(newRuntime(), "Z", source))
            .as("a key the source refuses passes through as its KeyNotPermitted, distinct from a failed acquisition")
            .isInstanceOf(harness.load(SCHEMA_PACKAGE + ".KeyNotPermitted"));
        assertThat(events).as("no connection is taken for a refused key: the refusal precedes all SQL").isEmpty();
    }

    @Test
    void dslFor_pinsOneConnectionPerDistinctKey_reusingWithinTheOperation() throws Throwable {
        Object tc = newOwnedConnections(Map.of("A", fakeDataSource("A", false), "B", fakeDataSource("B", false)));

        dslFor(tc, "A");
        dslFor(tc, "A"); // same key again within the operation
        dslFor(tc, "B");

        // A pinned once (second dslFor("A") reused it), B pinned once: N distinct keys -> N connections.
        assertThat(events).containsExactly(
            "getConnection:A", "connect:A",
            "getConnection:B", "connect:B");

        events.clear();
        releaseAll(tc);
        // The unified carrier's map is concurrent, so cross-key release order is unspecified;
        // within one key the unmount precedes the reset, and the reset the close: every tenant
        // entry is released through PinnedConnection.release() and inherits the backstop.
        assertThat(events).containsExactlyInAnyOrder(
            "disconnect:A", "reset:A", "close:A",
            "disconnect:B", "reset:B", "close:B");
        assertThat(events).containsSubsequence("disconnect:A", "reset:A", "close:A");
        assertThat(events).containsSubsequence("disconnect:B", "reset:B", "close:B");
    }

    @Test
    void releaseAll_evictsAFailedConnectionAndStillReleasesTheRest() throws Throwable {
        // B's disconnect fails; A's succeeds. releaseAll must release both (A returned, B evicted) and
        // rethrow, rather than let B's failed unmount orphan A or vice versa.
        Object tc = newOwnedConnections(Map.of("A", fakeDataSource("A", false), "B", fakeDataSource("B", true)));

        dslFor(tc, "A");
        dslFor(tc, "B");
        events.clear();

        assertThatThrownBy(() -> releaseAll(tc))
            .as("a tenant's disconnect failure surfaces after every connection has been released")
            .isInstanceOf(RuntimeException.class);

        assertThat(events)
            .as("A returns to its pool, B is evicted (aborted); neither is orphaned by the other's outcome")
            .containsExactlyInAnyOrder("disconnect:A", "reset:A", "close:A", "disconnect:B", "abort:B");
        assertThat(events.indexOf("disconnect:A")).isLessThan(events.indexOf("close:A"));
        assertThat(events.indexOf("disconnect:B")).isLessThan(events.indexOf("abort:B"));
    }

    @Test
    void releaseAll_twice_unmountsAndReturnsEachConnectionOnce() throws Throwable {
        Object tc = newOwnedConnections(Map.of());
        dslDefault(tc);
        events.clear();

        releaseAll(tc);
        releaseAll(tc);

        assertThat(events)
            .as("the drain removes each entry once, so the second releaseAll finds nothing to release")
            .containsExactly("disconnect:default", "reset:default", "close:default");
    }

    @Test
    void acquisitionAfterRelease_isRefused_withoutBorrowingOrMounting() throws Throwable {
        Object tc = newOwnedConnections(Map.of("A", fakeDataSource("A", false)));
        dslDefault(tc);
        releaseAll(tc);
        events.clear();

        assertThatThrownBy(() -> dslDefault(tc))
            .as("a straggler asking for the default connection after release is refused")
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("A connection was requested after the operation had released its connections.");
        assertThatThrownBy(() -> dslFor(tc, "A"))
            .as("so is one asking for a keyed connection the operation never pinned")
            .isInstanceOf(IllegalStateException.class);
        assertThat(events)
            .as("the refusal comes before the pool: nothing is borrowed, so nothing is left mounted")
            .isEmpty();
    }

    @Test
    void pinInFlightAcrossRelease_isAbortedAndRefused() throws Throwable {
        var entered = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        Object tc = newOwnedConnections(newRuntime(),
            source(Map.of("default", gated(fakeDataSource("default", false), entered, gate))));

        var late = CompletableFuture.runAsync(() -> {
            try {
                dslDefault(tc);
            } catch (Throwable e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("the acquisition is parked inside the pin").isTrue();
            // Release runs while the pin is in flight. Bounded, so a drain that waited on the pin's
            // map bin fails this case instead of hanging it.
            CompletableFuture.runAsync(() -> {
                try {
                    releaseAll(tc);
                } catch (Throwable e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }).get(5, TimeUnit.SECONDS);
        } finally {
            gate.countDown();
        }

        assertThatThrownBy(() -> late.get(5, TimeUnit.SECONDS))
            .as("the pin that completed after release is never handed out")
            .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(events)
            .as("the late pin aborts its own connection: evicted, never unmounted under a possibly-live"
                + " statement and never returned to the pool")
            .containsExactly("getConnection:default", "connect:default", "abort:default");
    }

    @Test
    void pinnedConnection_releaseThenAbort_returnsOnceAndNeverEvicts() throws Throwable {
        Object pinned = acquire(newRuntime(), "A", source(Map.of("A", fakeDataSource("A", false))));
        events.clear();

        invoke(() -> Accessible.method(pinned.getClass(), "release").invoke(pinned));
        invoke(() -> Accessible.method(pinned.getClass(), "abort").invoke(pinned));

        assertThat(events)
            .as("release and abort share one flag: the abort after a release does nothing")
            .containsExactly("disconnect:A", "reset:A", "close:A");
    }

    @Test
    void pinnedConnection_abortThenRelease_evictsOnceAndNeverUnmounts() throws Throwable {
        Object pinned = acquire(newRuntime(), "A", source(Map.of("A", fakeDataSource("A", false))));
        events.clear();

        invoke(() -> Accessible.method(pinned.getClass(), "abort").invoke(pinned));
        invoke(() -> Accessible.method(pinned.getClass(), "release").invoke(pinned));

        assertThat(events)
            .as("release and abort share one flag: the release after an abort does nothing")
            .containsExactly("abort:A");
    }

    @Test
    void sessionHandle_onAMountedConnection_returnsTheHandleWrittenAtMint() throws Throwable {
        Object tc = newOwnedConnections(Map.of("A", fakeDataSource("A", false)));

        Object dsl = dslForReturning(tc, "A");

        assertThat(sessionHandle(dsl, "Film.mountedPrincipal"))
            .as("the guarded accessor reads back exactly what the mount returned and the mint wrote")
            .isEqualTo("handle:{}");
    }

    @Test
    void sessionHandle_onAConnectionGraphitronNeverMounted_throwsLocatedInsteadOfBindingNull() {
        // The escape-hatch shape: a caller-supplied DSLContext whose Configuration graphitron
        // never mounted, so the handle slot is empty. The read must fail loudly, naming the field
        // coordinate, the sigil, and the owned entry points, never hand a service parameter null.
        org.jooq.DSLContext supplied = org.jooq.impl.DSL.using(SQLDialect.POSTGRES);

        assertThatThrownBy(() -> sessionHandle(supplied, "Query.sessionPrincipal"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Query.sessionPrincipal")
            .hasMessageContaining("$session")
            .hasMessageContaining("Graphitron.newOwnedExecutionInput")
            .hasMessageContaining("GraphitronRuntime.newGraphQL(schema)")
            .hasMessageContaining("Graphitron.newExecutionInput(dsl, ...)");
    }

    // --- driving helpers -------------------------------------------------------------------------

    private static Object newRuntime() throws Throwable {
        return runtimeClass.getConstructor(SQLDialect.class).newInstance(SQLDialect.POSTGRES);
    }

    /**
     * The request's source: a recording mounting source over {@code tenantPools} plus a
     * {@code "default"} pool for the default key, where the map does not name one.
     */
    private Object source(Map<String, DataSource> tenantPools) throws Exception {
        var pools = new LinkedHashMap<String, DataSource>(tenantPools);
        pools.putIfAbsent(RecordingSource.DEFAULT, fakeDataSource(RecordingSource.DEFAULT, false));
        return RecordingSource.create(harness, SCHEMA_PACKAGE, "{}", pools);
    }

    private Object newOwnedConnections(Map<String, DataSource> tenantPools) throws Throwable {
        return newOwnedConnections(newRuntime(), source(tenantPools));
    }

    private Object newOwnedConnections(Object runtime, Object source) throws Throwable {
        return ownedConnectionsClass.getConstructor(runtimeClass, commitPolicyClass, sourceClass())
            .newInstance(runtime, commitPolicyCommit, source);
    }

    private Object acquire(Object runtime, String tenant, Object source) throws Throwable {
        Class<?> keyClass = harness.load(SCHEMA_PACKAGE + ".ConnectionKey");
        return invoke(() -> Accessible.method(runtimeClass, "acquire", keyClass, sourceClass())
            .invoke(runtime, keyClass.getMethod("forTenant", Object.class).invoke(null, tenant), source));
    }

    private static Class<?> sourceClass() {
        return harness.load(SCHEMA_PACKAGE + ".ConnectionSource");
    }

    private void dslFor(Object tc, Object key) throws Throwable {
        dslForReturning(tc, key);
    }

    private Object dslForReturning(Object tc, Object key) throws Throwable {
        try {
            return ownedConnectionsClass.getMethod("dslFor", Object.class).invoke(tc, key);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Object sessionHandle(Object dsl, String fieldCoordinate) throws Throwable {
        try {
            return ownedConnectionsClass.getMethod("sessionHandle", org.jooq.DSLContext.class, String.class)
                .invoke(null, dsl, fieldCoordinate);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void dslDefault(Object tc) throws Throwable {
        invoke(() -> ownedConnectionsClass.getMethod("dslDefault").invoke(tc));
    }

    private void releaseAll(Object tc) throws Throwable {
        invoke(() -> ownedConnectionsClass.getMethod("releaseAll").invoke(tc));
    }

    private static Object invoke(ThrowingSupplier body) throws Throwable {
        try {
            return body.get();
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }

    // --- recording fakes -------------------------------------------------------------------------

    private DataSource fakeDataSource(String label, boolean failDisconnect) {
        return (DataSource) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    events.add("getConnection:" + label);
                    return fakeConnection(label, failDisconnect);
                }
                return objectMethodOrDefault(proxy, method, args, "fakeDataSource:" + label);
            });
    }

    /**
     * Wraps {@code inner} so {@code getConnection} signals {@code entered}, then parks until
     * {@code gate} opens: an acquisition held inside the pin, for racing it against release.
     */
    private DataSource gated(DataSource inner, CountDownLatch entered, CountDownLatch gate) {
        return (DataSource) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    entered.countDown();
                    if (!gate.await(10, TimeUnit.SECONDS)) {
                        throw new SQLException("gate never opened");
                    }
                }
                return method.invoke(inner, args);
            });
    }

    private Connection fakeConnection(String label, boolean failDisconnect) {
        boolean[] autoCommit = {false};
        boolean[] closed = {false};
        return (Connection) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                // The RecordingSource callbacks run prepareStatement for both mount and unmount;
                // the unmount SQL is the one that clears to the empty string. Label the phase off the SQL.
                // The reset release issues after the unmount is the third prepareStatement.
                case "prepareStatement" -> {
                    if (FakeSessionSettings.isReset((String) args[0])) {
                        events.add("reset:" + label);
                        yield fakePreparedStatement(false);
                    }
                    boolean disconnect = ((String) args[0]).contains("'', false");
                    events.add((disconnect ? "disconnect:" : "connect:") + label);
                    yield fakePreparedStatement(disconnect && failDisconnect);
                }
                case "createStatement" -> FakeSessionSettings.captureStatement(harness.classLoader(), List.of(), () -> {});
                case "setAutoCommit" -> { autoCommit[0] = (Boolean) args[0]; yield null; }
                case "getAutoCommit" -> autoCommit[0];
                case "getMetaData" -> FakeSessionSettings.postgres(harness.classLoader());
                case "isClosed" -> closed[0];
                case "close" -> { closed[0] = true; events.add("close:" + label); yield null; }
                case "abort" -> { closed[0] = true; events.add("abort:" + label); yield null; }
                default -> objectMethodOrDefault(proxy, method, args, label);
            });
    }

    private PreparedStatement fakePreparedStatement(boolean throwOnExecute) {
        return (PreparedStatement) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                case "execute" -> {
                    if (throwOnExecute) {
                        throw new SQLException("disconnect failed");
                    }
                    yield false;
                }
                default -> objectMethodOrDefault(proxy, method, args, "fakePreparedStatement");
            });
    }

    // --- the canonical tenant key: structural only, behaviour is pinned at the execution tier ---

    private static TypeSpec carrierOf(List<TypeSpec> units) {
        return units.stream()
            .filter(t -> ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME.equals(t.name()))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void multiTenantCarrierDeclaresThePublicCanonicalTenantKey() {
        var carrier = carrierOf(ConnectionRuntimeClassGenerator.generate(PACKAGE,
            SessionIdentityFixtures.recording(), ClassName.get(String.class),
            TenantColumnFixtures.STRING_KEY_COLUMN));

        assertThat(carrier.methodSpecs())
            .filteredOn(m -> "canonicalTenantKey".equals(m.name()))
            .singleElement()
            .satisfies(m -> {
                assertThat(m.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
                assertThat(m.returnType()).isEqualTo(ClassName.get(String.class));
            });
    }

    @Test
    void singleTenantCarrierHasNoCanonicalTenantKey() {
        var carrier = carrierOf(ConnectionRuntimeClassGenerator.generate(PACKAGE,
            SessionIdentityFixtures.recording()));

        assertThat(carrier.methodSpecs()).extracting(MethodSpec::name).doesNotContain("canonicalTenantKey");
    }

    @Test
    void aKeyTypeWithoutAColumnOrAColumnWithoutAKeyTypeIsRefused() {
        var hooks = SessionIdentityFixtures.recording();
        assertThatThrownBy(() -> ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks,
                ClassName.get(String.class), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks,
                (com.palantir.javapoet.TypeName) null, TenantColumnFixtures.STRING_KEY_COLUMN))
            .isInstanceOf(IllegalArgumentException.class);
    }

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
