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
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier coverage of the runtime's tenant-keyed acquisition primitive
 * ({@code GraphitronRuntime.acquireForTenant}) and the per-operation {@code TenantConnections} carrier.
 * Because these are emitted, the honest way to assert their behaviour is to compile the real emitted
 * {@code TypeSpec}s and drive them over fake JDBC. The units are generated with the
 * {@link RecordingSessionState} callback pair so the real mount/unmount runs at acquisition/release,
 * letting a fake {@code Connection} observe both phases as the {@code prepareStatement} calls the
 * fixture makes; the fake tenant {@code DataSource}s are keyed so "the right source per key" and
 * "one connection per distinct key" read straight off the call log.
 */
@UnitTier
class TenantConnectionsGeneratorTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> runtimeClass;
    private static Class<?> tenantConnectionsClass;
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
        tenantConnectionsClass = harness.load(SCHEMA_PACKAGE + ".TenantConnections");
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
    void acquireForTenant_selectsTheKeysSource_andUnknownKeyRaisesBeforeAnySql() throws Throwable {
        Object runtime = newRuntime(Map.of("A", fakeDataSource("A", false), "B", fakeDataSource("B", false)));

        acquireForTenant(runtime, "A", session());
        assertThat(events)
            .as("acquiring for key A pins A's source and mounts identity on it")
            .containsExactly("getConnection:A", "connect:A");

        events.clear();
        assertThatThrownBy(() -> acquireForTenant(runtime, "Z", session()))
            .as("an unknown tenant key is a request-level error, structurally distinct from acquisition failure")
            .isInstanceOf(NoSuchElementException.class);
        assertThat(events).as("no connection is acquired for an unknown key: the error precedes all SQL").isEmpty();
    }

    @Test
    void dslFor_pinsOneConnectionPerDistinctKey_reusingWithinTheOperation() throws Throwable {
        Object runtime = newRuntime(Map.of("A", fakeDataSource("A", false), "B", fakeDataSource("B", false)));
        Object tc = newTenantConnections(runtime, session());

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
        Object runtime = newRuntime(Map.of("A", fakeDataSource("A", false), "B", fakeDataSource("B", true)));
        Object tc = newTenantConnections(runtime, session());

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
        Object runtime = newRuntime(Map.of());
        Object tc = newTenantConnections(runtime, session());
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
        Object runtime = newRuntime(Map.of("A", fakeDataSource("A", false)));
        Object tc = newTenantConnections(runtime, session());
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
        Object runtime = newRuntime(gated(fakeDataSource("default", false), entered, gate), Map.of());
        Object tc = newTenantConnections(runtime, session());

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
        Object runtime = newRuntime(Map.of("A", fakeDataSource("A", false)));
        Object pinned = acquireForTenant(runtime, "A", session());
        events.clear();

        invoke(() -> pinned.getClass().getMethod("release").invoke(pinned));
        invoke(() -> pinned.getClass().getMethod("abort").invoke(pinned));

        assertThat(events)
            .as("release and abort share one flag: the abort after a release does nothing")
            .containsExactly("disconnect:A", "reset:A", "close:A");
    }

    @Test
    void pinnedConnection_abortThenRelease_evictsOnceAndNeverUnmounts() throws Throwable {
        Object runtime = newRuntime(Map.of("A", fakeDataSource("A", false)));
        Object pinned = acquireForTenant(runtime, "A", session());
        events.clear();

        invoke(() -> pinned.getClass().getMethod("abort").invoke(pinned));
        invoke(() -> pinned.getClass().getMethod("release").invoke(pinned));

        assertThat(events)
            .as("release and abort share one flag: the release after an abort does nothing")
            .containsExactly("abort:A");
    }

    @Test
    void sessionHandle_onAMountedConnection_returnsTheHandleWrittenAtMint() throws Throwable {
        Object runtime = newRuntime(Map.of("A", fakeDataSource("A", false)));
        Object tc = newTenantConnections(runtime, session());

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

    private Object newRuntime(Map<String, DataSource> tenantSources) throws Throwable {
        return newRuntime(fakeDataSource("default", false), tenantSources);
    }

    private Object newRuntime(DataSource defaultDs, Map<String, DataSource> tenantSources) throws Throwable {
        return runtimeClass.getConstructor(DataSource.class, Map.class, SQLDialect.class)
            .newInstance(defaultDs, new LinkedHashMap<Object, DataSource>(tenantSources), SQLDialect.POSTGRES);
    }

    /** The request's session state: callbacks that run the recording statements and return a handle. */
    private static Object session() throws Exception {
        return RecordingSessionState.create(harness, SCHEMA_PACKAGE, "{}");
    }

    private Object newTenantConnections(Object runtime, Object session) throws Throwable {
        return tenantConnectionsClass.getConstructor(runtimeClass, commitPolicyClass, sessionStateClass())
            .newInstance(runtime, commitPolicyCommit, session);
    }

    private Object acquireForTenant(Object runtime, Object key, Object session) throws Throwable {
        return invoke(() -> runtimeClass.getMethod("acquireForTenant", Object.class, sessionStateClass())
            .invoke(runtime, key, session));
    }

    private static Class<?> sessionStateClass() {
        return harness.load(SCHEMA_PACKAGE + ".SessionState");
    }

    private void dslFor(Object tc, Object key) throws Throwable {
        dslForReturning(tc, key);
    }

    private Object dslForReturning(Object tc, Object key) throws Throwable {
        try {
            return tenantConnectionsClass.getMethod("dslFor", Object.class).invoke(tc, key);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Object sessionHandle(Object dsl, String fieldCoordinate) throws Throwable {
        try {
            return tenantConnectionsClass.getMethod("sessionHandle", org.jooq.DSLContext.class, String.class)
                .invoke(null, dsl, fieldCoordinate);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void dslDefault(Object tc) throws Throwable {
        invoke(() -> tenantConnectionsClass.getMethod("dslDefault").invoke(tc));
    }

    private void releaseAll(Object tc) throws Throwable {
        invoke(() -> tenantConnectionsClass.getMethod("releaseAll").invoke(tc));
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
        return (Connection) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                // The RecordingSessionState callbacks run prepareStatement for both mount and unmount;
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
                case "close" -> { events.add("close:" + label); yield null; }
                case "abort" -> { events.add("abort:" + label); yield null; }
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
            .filter(t -> ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME.equals(t.name()))
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
                null, TenantColumnFixtures.STRING_KEY_COLUMN))
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
