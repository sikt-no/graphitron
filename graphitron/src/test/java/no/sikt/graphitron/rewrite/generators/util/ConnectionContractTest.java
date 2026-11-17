package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.emit.SessionIdentityRows;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.session.SessionIdentityFixtures;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The connection contract as the carrier keeps it, driven on emitted code: a {@code ConnectionSource}
 * is asked once per distinct key, lazily; the lease's handle reaches {@code $session} reads; every
 * lease is released exactly once on every path; {@code KeyNotPermitted} answers as a field error and
 * is remembered per key, so the source is asked once; any other throw rejects the request and
 * nothing more is acquired; a tenanted build's {@code ConnectionSource.of} copies its tenants in
 * order and refuses a {@code null}, and an untenanted source's {@code tenants()} throws; a throwing release orphans no other
 * lease; a lease with autocommit off is refused and released; an aborted lease is aborted before its
 * release.
 */
@UnitTier
class ConnectionContractTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness untenanted;
    private static EmittedCodeHarness tenanted;

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void compile() {
        untenanted = compile(SessionIdentityFixtures.recording(), null);
        tenanted = compile(SessionIdentityFixtures.recording(), ClassName.get(String.class));
    }

    private static EmittedCodeHarness compile(SessionIdentityRows hooks, ClassName tenantType) {
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        var runtime = tenantType == null
            ? ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks)
            : ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks, tenantType, TenantColumnFixtures.STRING_KEY_COLUMN);
        for (TypeSpec spec : runtime) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, tenantType, hooks)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        if (tenantType != null) {
            for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
                units.put(PACKAGE + ".util." + spec.name(), spec);
            }
        }
        return EmittedCodeHarness.compile(units);
    }

    @AfterAll
    static void close() {
        if (untenanted != null) {
            untenanted.close();
        }
        if (tenanted != null) {
            tenanted.close();
        }
    }

    @BeforeEach
    void reset() {
        events.clear();
    }

    // --- lazy, once per key ----------------------------------------------------------------------

    @Test
    void nothingIsAcquiredUntilAFieldAsks_andAKeyIsAskedOnce() throws Throwable {
        var carrier = carrier(untenanted, source(untenanted, key -> lease(untenanted, "default", true, null)));
        assertThat(events).isEmpty();

        Object first = invoke(() -> carrier.getClass().getMethod("dslDefault").invoke(carrier));
        Object second = invoke(() -> carrier.getClass().getMethod("dslDefault").invoke(carrier));

        assertThat(second).isSameAs(first);
        assertThat(events).containsExactly("acquire:" + emptyKey(untenanted));
        releaseAll(carrier);
    }

    @Test
    void distinctKeysAreAskedOnceEach() throws Throwable {
        var carrier = tenantedCarrier(
            source(tenanted, key -> lease(tenanted, String.valueOf(tenantOf(key)), true, null)));

        dslFor(carrier, "A");
        dslFor(carrier, "A");
        dslFor(carrier, "B");

        assertThat(events).containsExactly("acquire:A", "acquire:B");
        releaseAll(carrier);
    }

    // --- the handle ------------------------------------------------------------------------------

    @Test
    void theLeasesHandleIsWhatSessionReadsSee() throws Throwable {
        var carrier = carrier(untenanted, source(untenanted, key -> lease(untenanted, "default", true, "the-handle")));
        Object dsl = invoke(() -> carrier.getClass().getMethod("dslDefault").invoke(carrier));

        Object handle = invoke(() -> carrier.getClass().getMethod("sessionHandle", DSLContext.class, String.class)
            .invoke(null, dsl, "Query.me"));

        assertThat(handle).isEqualTo("the-handle");
        releaseAll(carrier);
    }

    @Test
    void aLeaseWithoutAHandleFailsTheSessionReadLoudly() throws Throwable {
        var carrier = carrier(untenanted, source(untenanted, key -> lease(untenanted, "default", true, null)));
        Object dsl = invoke(() -> carrier.getClass().getMethod("dslDefault").invoke(carrier));

        assertThatThrownBy(() -> invoke(() -> carrier.getClass()
                .getMethod("sessionHandle", DSLContext.class, String.class).invoke(null, dsl, "Query.me")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Query.me")
            .hasMessageContaining("gave none");
        releaseAll(carrier);
    }

    // --- release exactly once --------------------------------------------------------------------

    @Test
    void everyLeaseIsReleasedExactlyOnce_whateverReleasesItAgain() throws Throwable {
        var carrier = carrier(untenanted, source(untenanted, key -> lease(untenanted, "default", true, null)));
        invoke(() -> carrier.getClass().getMethod("dslDefault").invoke(carrier));

        releaseAll(carrier);
        releaseAll(carrier);

        assertThat(events).containsExactly("acquire:" + emptyKey(untenanted), "release:default");
    }

    @Test
    void aThrowingReleaseOrphansNoOtherLease_andSurfacesAfterAllAreAttempted() throws Throwable {
        var carrier = tenantedCarrier(source(tenanted, key -> {
            String tenant = String.valueOf(tenantOf(key));
            return lease(tenanted, tenant, true, null, tenant.equals("A") ? new IllegalStateException("A will not go") : null);
        }));
        dslFor(carrier, "A");
        dslFor(carrier, "B");

        assertThatThrownBy(() -> releaseAll(carrier)).hasMessageContaining("A will not go");

        assertThat(events).contains("release:A", "release:B");
    }

    // --- refusals --------------------------------------------------------------------------------

    @Test
    void keyNotPermitted_answersAsAClientErrorAtTheFieldThatAsked_andTheRequestRuns() throws Throwable {
        Class<?> refusal = tenanted.load(SCHEMA_PACKAGE + ".KeyNotPermitted");
        var carrier = tenantedCarrier(source(tenanted, key -> {
            String tenant = String.valueOf(tenantOf(key));
            if (tenant.equals("A")) {
                throw (RuntimeException) refusal.getConstructor(String.class).newInstance("not yours");
            }
            return lease(tenanted, tenant, true, null);
        }));

        assertThatThrownBy(() -> dslFor(carrier, "A"))
            .isInstanceOf(tenanted.load(SCHEMA_PACKAGE + "." + GraphitronClientExceptionClassGenerator.CLASS_NAME))
            .hasMessage("not yours");
        dslFor(carrier, "B");

        assertThat(acquisitionFailure(carrier)).isEmpty();
        releaseAll(carrier);
        assertThat(events).containsExactly("acquire:B", "release:B");
    }

    @Test
    void aRefusalIsRememberedPerKey_soTheSourceIsAskedOnce_andAnswersTheSameErrorAgain() throws Throwable {
        Class<?> refusal = tenanted.load(SCHEMA_PACKAGE + ".KeyNotPermitted");
        Class<?> clientError = tenanted.load(SCHEMA_PACKAGE + "." + GraphitronClientExceptionClassGenerator.CLASS_NAME);
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        var carrier = tenantedCarrier(source(tenanted, key -> {
            String tenant = String.valueOf(tenantOf(key));
            if (tenant.equals("A")) {
                asked.incrementAndGet();
                throw (RuntimeException) refusal.getConstructor(String.class).newInstance("not yours");
            }
            return lease(tenanted, tenant, true, null);
        }));

        assertThatThrownBy(() -> dslFor(carrier, "A")).isInstanceOf(clientError).hasMessage("not yours");
        assertThatThrownBy(() -> dslFor(carrier, "A")).isInstanceOf(clientError).hasMessage("not yours");
        dslFor(carrier, "B");

        assertThat(asked).as("the source is never asked twice for one key in one request").hasValue(1);
        releaseAll(carrier);
        assertThat(events).containsExactly("acquire:B", "release:B");
    }

    @Test
    void anUntenantedSourcesTenants_throws_sinceNothingFansOut() throws Throwable {
        Object source = RecordingSource.plain(untenanted, SCHEMA_PACKAGE, Map.of());

        assertThatThrownBy(() -> invoke(() -> untenanted.load(SCHEMA_PACKAGE + ".ConnectionSource")
            .getMethod("tenants").invoke(source)))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessage("this build has no tenant column, so nothing fans out");
    }

    @Test
    void aTenantedSourceOf_copiesTheTenantsInOrder_unmodifiably() throws Throwable {
        var tenants = new ArrayList<>(List.of("B", "A", "C", "A"));
        Object source = sourceOf(tenants);
        tenants.add("D");

        @SuppressWarnings("unchecked")
        var named = (Set<Object>) invoke(() -> sourceInterface().getMethod("tenants").invoke(source));
        assertThat(named).as("copied, in iteration order, duplicates dropped").containsExactly("B", "A", "C");
        assertThatThrownBy(() -> named.add("E")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aTenantedSourceOf_refusesANullTenant() {
        var tenants = new ArrayList<String>();
        tenants.add("A");
        tenants.add(null);

        assertThatThrownBy(() -> sourceOf(tenants)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sourceOf(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aTenantedSourceOf_leasesThroughItsAcquire() throws Throwable {
        Object source = sourceOf(List.of("A"));
        var carrier = tenantedCarrier(source);

        dslFor(carrier, "A");
        releaseAll(carrier);

        assertThat(events).containsExactly("acquire:A", "release:A");
    }

    private Class<?> sourceInterface() {
        return tenanted.load(SCHEMA_PACKAGE + ".ConnectionSource");
    }

    /** {@code ConnectionSource.of(acquire, tenants)} in the tenanted build, leasing a fake per key. */
    private Object sourceOf(java.util.Collection<String> tenants) throws Throwable {
        Class<?> acquireInterface = tenanted.load(SCHEMA_PACKAGE + ".ConnectionSource$Acquire");
        Object acquire = Proxy.newProxyInstance(tenanted.classLoader(), new Class<?>[]{acquireInterface},
            (proxy, method, args) -> switch (method.getName()) {
                case "acquire" -> {
                    String tenant = String.valueOf(tenantOf(args[0]));
                    events.add("acquire:" + tenant);
                    yield lease(tenanted, tenant, true, null);
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> "Acquire";
            });
        return invoke(() -> sourceInterface().getMethod("of", acquireInterface, java.util.Collection.class)
            .invoke(null, acquire, tenants));
    }

    @Test
    void aThrowingAcquire_rejectsTheRequest_andNothingMoreIsAcquired() throws Throwable {
        Class<?> failed = tenanted.load(SCHEMA_PACKAGE + ".AcquisitionFailedException");
        var cause = new SQLException("pool exhausted");
        var carrier = tenantedCarrier(source(tenanted, key -> {
            events.add("acquire:" + tenantOf(key));
            throw cause;
        }));

        assertThatThrownBy(() -> dslFor(carrier, "A")).isInstanceOf(failed).hasCause(cause);
        assertThatThrownBy(() -> dslFor(carrier, "B")).isInstanceOf(failed).hasCause(cause);

        assertThat(events).containsExactly("acquire:A");
        assertThat(acquisitionFailure(carrier)).isPresent();
        Throwable remembered = (Throwable) acquisitionFailure(carrier).get();
        Object chain = invoke(() -> carrier.getClass().getMethod("rejectionCause", Throwable.class)
            .invoke(null, new RuntimeException(remembered)));
        assertThat(chain).isEqualTo(Optional.of(cause));
        releaseAll(carrier);
    }

    @Test
    void aLeaseWithAutocommitOff_isRefusedAsAFailedAcquisition_andReleased() throws Throwable {
        Class<?> failed = untenanted.load(SCHEMA_PACKAGE + ".AcquisitionFailedException");
        var carrier = carrier(untenanted, source(untenanted, key -> lease(untenanted, "default", false, null)));

        assertThatThrownBy(() -> invoke(() -> carrier.getClass().getMethod("dslDefault").invoke(carrier)))
            .isInstanceOf(failed)
            .hasRootCauseMessage("the connection source leased a connection with autocommit off");

        assertThat(events).containsExactly("acquire:" + emptyKey(untenanted), "release:default");
    }

    // --- abort -----------------------------------------------------------------------------------

    @Test
    void anAbortedLeaseIsAbortedThroughJdbcBeforeItsRelease_andReleasedOnce() throws Throwable {
        Object runtime = untenanted.load(SCHEMA_PACKAGE + ".GraphitronRuntime").getConstructor(SQLDialect.class)
            .newInstance(SQLDialect.POSTGRES);
        Class<?> keyClass = untenanted.load(SCHEMA_PACKAGE + ".ConnectionKey");
        Object key = keyClass.getMethod("defaultSource").invoke(null);
        Object source = source(untenanted, k -> lease(untenanted, "default", true, null));
        Object pinned = invoke(() -> Accessible.method(runtime.getClass(), "acquire", keyClass, untenanted.load(SCHEMA_PACKAGE + ".ConnectionSource"))
            .invoke(runtime, key, source));

        Accessible.method(pinned.getClass(), "abort").invoke(pinned);
        Accessible.method(pinned.getClass(), "release").invoke(pinned);

        assertThat(events).containsExactly("acquire:" + emptyKey(untenanted), "abort:default", "release:default");
    }

    @Test
    void aLeaseOfItsOwnDialectWinsOverTheRuntimes() throws Throwable {
        Object runtime = untenanted.load(SCHEMA_PACKAGE + ".GraphitronRuntime").getConstructor(SQLDialect.class)
            .newInstance(SQLDialect.POSTGRES);
        Class<?> keyClass = untenanted.load(SCHEMA_PACKAGE + ".ConnectionKey");
        Class<?> leaseClass = untenanted.load(SCHEMA_PACKAGE + ".Lease");
        Class<?> releaseClass = untenanted.load(SCHEMA_PACKAGE + ".Lease$Release");
        Object source = source(untenanted, k -> leaseClass
            .getMethod("of", Connection.class, String.class, SQLDialect.class,
                org.jooq.conf.Settings.class, releaseClass)
            .invoke(null, connection("own", true), null, SQLDialect.H2, null, release(untenanted, "own", null)));
        Object pinned = invoke(() -> Accessible.method(runtime.getClass(), "acquire", keyClass, untenanted.load(SCHEMA_PACKAGE + ".ConnectionSource"))
            .invoke(runtime, keyClass.getMethod("defaultSource").invoke(null), source));

        assertThat(Accessible.method(pinned.getClass(), "dialect").invoke(pinned)).isEqualTo(SQLDialect.H2);
        assertThat(Accessible.method(pinned.getClass(), "settings").invoke(pinned)).isNotNull();
        Accessible.method(pinned.getClass(), "release").invoke(pinned);
    }

    // --- driving helpers -------------------------------------------------------------------------

    private Object carrier(EmittedCodeHarness harness, Object source) throws Throwable {
        Class<?> runtimeClass = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
        Object runtime = runtimeClass.getConstructor(SQLDialect.class).newInstance(SQLDialect.POSTGRES);
        return harness.load(SCHEMA_PACKAGE + ".OwnedConnections")
            .getConstructor(runtimeClass, commitPolicyClass(harness), harness.load(SCHEMA_PACKAGE + ".ConnectionSource"))
            .newInstance(runtime, commit(harness), source);
    }

    private Object tenantedCarrier(Object source) throws Throwable {
        return carrier(tenanted, source);
    }

    private static Class<?> commitPolicyClass(EmittedCodeHarness harness) {
        Class<?> providerClass = harness.load(SCHEMA_PACKAGE + ".GraphitronTransactionProvider");
        return java.util.Arrays.stream(providerClass.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("CommitPolicy"))
            .findFirst().orElseThrow();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object commit(EmittedCodeHarness harness) {
        return Enum.valueOf((Class) commitPolicyClass(harness), "COMMIT");
    }

    private static void dslFor(Object carrier, String tenant) throws Throwable {
        invoke(() -> carrier.getClass().getMethod("dslFor", String.class).invoke(carrier, tenant));
    }

    private static void releaseAll(Object carrier) throws Throwable {
        invoke(() -> carrier.getClass().getMethod("releaseAll").invoke(carrier));
    }

    private static Optional<?> acquisitionFailure(Object carrier) throws Throwable {
        return (Optional<?>) invoke(() -> carrier.getClass().getMethod("requestRejection").invoke(carrier));
    }

    /** The key's tenant, or null for a key without one, which a build with no tenant column always is. */
    private static Object tenantOf(Object key) throws ReflectiveOperationException {
        var components = key.getClass().getRecordComponents();
        if (java.util.Arrays.stream(components).noneMatch(c -> c.getName().equals("tenant"))) {
            return null;
        }
        return ((Optional<?>) key.getClass().getMethod("tenant").invoke(key)).orElse(null);
    }

    private static String emptyKey(EmittedCodeHarness harness) throws ReflectiveOperationException {
        Object key = harness.load(SCHEMA_PACKAGE + ".ConnectionKey").getMethod("defaultSource").invoke(null);
        return String.valueOf(tenantOf(key));
    }

    /** A generated {@code ConnectionSource} over {@code leases}, recording each key it is asked for. */
    private Object source(EmittedCodeHarness harness, ThrowingFunction leases) {
        Class<?> sourceInterface = harness.load(SCHEMA_PACKAGE + ".ConnectionSource");
        return Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{sourceInterface},
            (proxy, method, args) -> switch (method.getName()) {
                case "acquire" -> {
                    Object key = args[0];
                    Object lease = leases.apply(key);
                    if (lease != null && !events.contains("acquire:" + tenantOf(key))) {
                        events.add("acquire:" + tenantOf(key));
                    }
                    yield lease;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "RecordingSource";
                default -> null;
            });
    }

    private Object lease(EmittedCodeHarness harness, String label, boolean autoCommit, String handle) throws Exception {
        return lease(harness, label, autoCommit, handle, null);
    }

    private Object lease(EmittedCodeHarness harness, String label, boolean autoCommit, String handle,
                         RuntimeException releaseFailure) throws Exception {
        Class<?> leaseClass = harness.load(SCHEMA_PACKAGE + ".Lease");
        Class<?> releaseClass = harness.load(SCHEMA_PACKAGE + ".Lease$Release");
        return leaseClass.getMethod("of", Connection.class, String.class, releaseClass)
            .invoke(null, connection(label, autoCommit), handle, release(harness, label, releaseFailure));
    }

    private Object release(EmittedCodeHarness harness, String label, RuntimeException failure) {
        Class<?> releaseClass = harness.load(SCHEMA_PACKAGE + ".Lease$Release");
        return Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{releaseClass},
            (proxy, method, args) -> switch (method.getName()) {
                case "release" -> {
                    events.add("release:" + label);
                    if (failure != null) {
                        throw failure;
                    }
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> "Release:" + label;
            });
    }

    private Connection connection(String label, boolean autoCommit) {
        boolean[] mode = {autoCommit};
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getAutoCommit" -> mode[0];
                case "setAutoCommit" -> { mode[0] = (Boolean) args[0]; yield null; }
                case "abort" -> { events.add("abort:" + label); yield null; }
                case "isValid" -> true;
                case "isClosed" -> false;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> label;
                default -> null;
            });
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

    @FunctionalInterface
    private interface ThrowingFunction {
        Object apply(Object key) throws Exception;
    }
}
