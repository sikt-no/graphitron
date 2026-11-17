package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.session.SessionIdentityFixtures;
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
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
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
 * Direct-carrier proofs for routed acquisition's two per-tenant guarantees, over the real emitted
 * multi-tenant runtime and fake JDBC (the {@link TenantScatterSubstrateTest} pattern): a key
 * outside the request tenant set is refused with the client-error type before any connection is
 * taken, ahead of the source's own refusal so an unauthorized caller cannot probe which tenants
 * the source hosts; and the source's mount receives the key it is mounting for, whose tenant is
 * {@code Optional.empty()} for the default source, one mount per key even when keys share a
 * {@code DataSource}. A request's default tenant serves {@code dslGlobal()} through the same
 * entry {@code dslFor} uses for that tenant, and leaves {@code dslDefault()} on the default source.
 */
@UnitTier
class TenantAuthorizationSubstrateTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> runtimeClass;
    private static Class<?> ownedConnectionsClass;
    private static Class<?> requestTenantsClass;
    private static Class<?> commitPolicyClass;
    private static Class<?> clientExceptionClass;
    private static Object commitPolicyCommit;

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void compile() {
        var hooks = SessionIdentityFixtures.recording();
        var tenantType = ClassName.get(String.class);
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks, tenantType,
                TenantColumnFixtures.STRING_KEY_COLUMN)) {
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
        // The fan-out failure path hands its throw to the router's log guard.
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        // The tenant key's canonical form is the column trip, held by the util package's ColumnValues.
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compile(units);
        runtimeClass = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
        ownedConnectionsClass = harness.load(SCHEMA_PACKAGE + ".OwnedConnections");
        requestTenantsClass = harness.load(SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.REQUEST_TENANTS_CLASS_NAME);
        clientExceptionClass = harness.load(SCHEMA_PACKAGE + "." + GraphitronClientExceptionClassGenerator.CLASS_NAME);
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
    void reset() {
        events.clear();
        RecordingSource.mountedTenants(harness).clear();
    }

    @Test
    void keyOutsideTheRequestSet_isRefusedBeforeAnyConnectionIsTaken() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Map.of("A", fakeDataSource("A"), "B", fakeDataSource("B"))),
            Set.of("A"));

        assertThatThrownBy(() -> dslFor(tc, "B"))
            .isInstanceOf(clientExceptionClass)
            .hasMessageContaining("'B'")
            .hasMessageContaining("not permitted");
        assertThat(events).as("no connection to a refused tenant is ever opened").isEmpty();
        assertThat(RecordingSource.mountedTenants(harness)).isEmpty();
        releaseAll(tc);
    }

    @Test
    void unhostedKeyOutsideTheSet_getsTheSameRefusalAsAHostedOne_soHostingCannotBeProbed() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Map.of("A", fakeDataSource("A"))), Set.of("A"));

        assertThatThrownBy(() -> dslFor(tc, "Z"))
            .as("the membership check runs ahead of the source's refusal")
            .isInstanceOf(clientExceptionClass)
            .hasMessageContaining("not permitted");
        releaseAll(tc);
    }

    @Test
    void keyInsideTheSetTheSourceRefuses_answersWithTheSourcesRefusalAsAClientError() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Map.of("A", fakeDataSource("A"))), Set.of("A", "Z"));

        assertThatThrownBy(() -> dslFor(tc, "Z"))
            .as("the source's KeyNotPermitted reaches the field as a client error carrying its message")
            .isInstanceOf(clientExceptionClass)
            .hasMessage("Tenant 'Z' is not hosted here.");
        assertThat(events).isEmpty();
        releaseAll(tc);
    }

    @Test
    void mountReceivesTheRoutedKey_andEmptyForTheDefaultSource() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Map.of("A", fakeDataSource("A"))), Set.of("A"));

        dslFor(tc, "A");
        invoke(() -> ownedConnectionsClass.getMethod("dslDefault").invoke(tc));

        assertThat(RecordingSource.mountedTenants(harness))
            .containsExactly(Optional.of("A"), Optional.empty());
        releaseAll(tc);
    }

    @Test
    void keysSharingOneDataSource_getOneConnectionAndOneMountEach_withTheirOwnKey() throws Throwable {
        DataSource shared = fakeDataSource("shared");
        var sources = new LinkedHashMap<String, DataSource>();
        sources.put("A", shared);
        sources.put("B", shared);
        Object tc = newOwnedConnections(newRuntime(sources), Set.of("A", "B"));

        dslFor(tc, "A");
        dslFor(tc, "B");
        dslFor(tc, "A");

        assertThat(events.stream().filter(e -> e.equals("getConnection:shared")).count())
            .as("session identity is per (connection, tenant): one pin per key, reused within the key")
            .isEqualTo(2);
        assertThat(RecordingSource.mountedTenants(harness)).containsExactly(Optional.of("A"), Optional.of("B"));
        releaseAll(tc);
    }

    // --- the request's default tenant ------------------------------------------------------------

    @Test
    void aDefaultTenantOutsideTheSet_isRefusedBeforeAnyCarrierExists() {
        assertThatThrownBy(() -> withDefault(Set.of("A"), "B"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'B'")
            .hasMessageContaining("not in the request tenant set");
        assertThat(events).isEmpty();
    }

    @Test
    void dslGlobal_pinsTheDefaultTenantsSource_andMountsForIt() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Map.of("A", fakeDataSource("A"), "B", fakeDataSource("B"))),
            withDefault(Set.of("A", "B"), "B"));

        dslGlobal(tc);

        assertThat(events).containsExactly("getConnection:B");
        assertThat(RecordingSource.mountedTenants(harness)).containsExactly(Optional.of("B"));
        releaseAll(tc);
    }

    @Test
    void dslGlobalThenDslForTheSameTenant_takesOneConnectionAndRunsOneMount() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Map.of("A", fakeDataSource("A"))),
            withDefault(Set.of("A"), "A"));

        Object global = dslGlobal(tc);
        Object routed = dslFor(tc, "A");

        assertThat(routed).as("one entry per tenant key, shared by both acquisitions").isSameAs(global);
        assertThat(events).containsExactly("getConnection:A");
        assertThat(RecordingSource.mountedTenants(harness)).containsExactly(Optional.of("A"));
        releaseAll(tc);
    }

    @Test
    void dslDefault_staysOnTheDefaultSource_whenTheRequestNamesADefaultTenant() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Map.of("A", fakeDataSource("A"))),
            withDefault(Set.of("A"), "A"));

        invoke(() -> ownedConnectionsClass.getMethod("dslDefault").invoke(tc));

        assertThat(events).containsExactly("getConnection:default");
        assertThat(RecordingSource.mountedTenants(harness)).containsExactly(Optional.empty());
        releaseAll(tc);
    }

    @Test
    void dslGlobal_withoutADefaultTenant_pinsTheDefaultSource() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Map.of("A", fakeDataSource("A"))), Set.of("A"));

        dslGlobal(tc);

        assertThat(events).containsExactly("getConnection:default");
        assertThat(RecordingSource.mountedTenants(harness)).containsExactly(Optional.empty());
        releaseAll(tc);
    }

    // --- driving helpers -------------------------------------------------------------------------

    /** A runtime, paired with the request source over {@code tenantPools} and a default pool. */
    private Hosted newRuntime(Map<String, DataSource> tenantPools) throws Throwable {
        Object runtime = runtimeClass.getConstructor(SQLDialect.class, Settings.class, int.class, Duration.class)
            .newInstance(SQLDialect.POSTGRES, new Settings(), 2, Duration.ofSeconds(10));
        var pools = new LinkedHashMap<String, DataSource>(tenantPools);
        pools.put(RecordingSource.DEFAULT, fakeDataSource(RecordingSource.DEFAULT));
        return new Hosted(runtime, RecordingSource.create(harness, SCHEMA_PACKAGE, "{}", pools));
    }

    private record Hosted(Object runtime, Object source) {}

    private Object newOwnedConnections(Hosted hosted, Set<String> tenants) throws Throwable {
        return newOwnedConnections(hosted,
            invoke(() -> requestTenantsClass.getMethod("of", java.util.Collection.class).invoke(null, tenants)));
    }

    private Object newOwnedConnections(Hosted hosted, Object requestTenants) throws Throwable {
        Class<?> sourceClass = harness.load(SCHEMA_PACKAGE + ".ConnectionSource");
        return ownedConnectionsClass.getConstructor(runtimeClass, commitPolicyClass, requestTenantsClass, sourceClass)
            .newInstance(hosted.runtime(), commitPolicyCommit, requestTenants, hosted.source());
    }

    private static Object withDefault(Set<String> tenants, String defaultTenant) throws Throwable {
        return invoke(() -> requestTenantsClass.getMethod("withDefault", java.util.Collection.class, String.class)
            .invoke(null, tenants, defaultTenant));
    }

    private Object dslGlobal(Object tc) throws Throwable {
        return invoke(() -> ownedConnectionsClass.getMethod("dslGlobal").invoke(tc));
    }

    private Object dslFor(Object tc, Object key) throws Throwable {
        return invoke(() -> ownedConnectionsClass.getMethod("dslFor", String.class).invoke(tc, key));
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

    private DataSource fakeDataSource(String label) {
        return (DataSource) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    events.add("getConnection:" + label);
                    return fakeConnection(label);
                }
                return objectMethodOrDefault(proxy, method, args, "fakeDataSource:" + label);
            });
    }

    private Connection fakeConnection(String label) {
        return (Connection) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> fakePreparedStatement();
                case "createStatement" -> FakeSessionSettings.captureStatement(harness.classLoader(), List.of(), () -> {});
                case "getAutoCommit" -> true;
                case "getMetaData" -> FakeSessionSettings.postgres(harness.classLoader());
                default -> objectMethodOrDefault(proxy, method, args, label + "-conn");
            });
    }

    private PreparedStatement fakePreparedStatement() {
        return (PreparedStatement) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> objectMethodOrDefault(proxy, method, args, "fakePreparedStatement"));
    }

    private static Object objectMethodOrDefault(Object proxy, java.lang.reflect.Method method, Object[] args,
                                                String label) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> label;
            default -> {
                Class<?> type = method.getReturnType();
                if (!type.isPrimitive() || type == void.class) {
                    yield null;
                }
                yield type == boolean.class ? (Object) false : (Object) 0;
            }
        };
    }
}
