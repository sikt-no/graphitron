package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.session.SessionHooksFixtures;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.SQLDialect;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct-carrier proofs for routed acquisition's two per-tenant guarantees, over the real emitted
 * multi-tenant runtime and fake JDBC (the {@link TenantScatterSubstrateTest} pattern): a key
 * outside the request tenant set is refused with the client-error type before any connection is
 * taken, ahead of the hosting check so an unauthorized caller cannot probe which tenants are
 * hosted; and a mount declaring the tenant slot receives the key it is mounting for, or
 * {@code Optional.empty()} for the default source, one mount per key even when keys share a
 * {@code DataSource}.
 */
@UnitTier
class TenantAuthorizationSubstrateTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> runtimeClass;
    private static Class<?> tenantConnectionsClass;
    private static Class<?> commitPolicyClass;
    private static Class<?> clientExceptionClass;
    private static Object commitPolicyCommit;

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void compile() {
        var hooks = SessionHooksFixtures.recordingTenantSlotHooks();
        var tenantType = ClassName.get(String.class);
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks, tenantType)) {
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
        harness = EmittedCodeHarness.compile(units);
        runtimeClass = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
        tenantConnectionsClass = harness.load(SCHEMA_PACKAGE + ".TenantConnections");
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
        RecordingHookFixture.MOUNTED_TENANTS.clear();
    }

    @Test
    void keyOutsideTheRequestSet_isRefusedBeforeAnyConnectionIsTaken() throws Throwable {
        Object tc = newTenantConnections(newRuntime(Map.of("A", fakeDataSource("A"), "B", fakeDataSource("B"))),
            Set.of("A"));

        assertThatThrownBy(() -> dslFor(tc, "B"))
            .isInstanceOf(clientExceptionClass)
            .hasMessageContaining("'B'")
            .hasMessageContaining("not permitted");
        assertThat(events).as("no connection to a refused tenant is ever opened").isEmpty();
        assertThat(RecordingHookFixture.MOUNTED_TENANTS).isEmpty();
        releaseAll(tc);
    }

    @Test
    void unhostedKeyOutsideTheSet_getsTheSameRefusalAsAHostedOne_soHostingCannotBeProbed() throws Throwable {
        Object tc = newTenantConnections(newRuntime(Map.of("A", fakeDataSource("A"))), Set.of("A"));

        assertThatThrownBy(() -> dslFor(tc, "Z"))
            .as("the membership check runs ahead of the runtime's hosting check")
            .isInstanceOf(clientExceptionClass)
            .hasMessageContaining("not permitted");
        releaseAll(tc);
    }

    @Test
    void unhostedKeyInsideTheSet_stillFailsTheHostingCheck() throws Throwable {
        Object tc = newTenantConnections(newRuntime(Map.of("A", fakeDataSource("A"))), Set.of("A", "Z"));

        assertThatThrownBy(() -> dslFor(tc, "Z")).isInstanceOf(NoSuchElementException.class);
        releaseAll(tc);
    }

    @Test
    void mountReceivesTheRoutedKey_andEmptyForTheDefaultSource() throws Throwable {
        Object tc = newTenantConnections(newRuntime(Map.of("A", fakeDataSource("A"))), Set.of("A"));

        dslFor(tc, "A");
        invoke(() -> tenantConnectionsClass.getMethod("dslDefault").invoke(tc));

        assertThat(RecordingHookFixture.MOUNTED_TENANTS)
            .containsExactly(Optional.of("A"), Optional.empty());
        releaseAll(tc);
    }

    @Test
    void keysSharingOneDataSource_getOneConnectionAndOneMountEach_withTheirOwnKey() throws Throwable {
        DataSource shared = fakeDataSource("shared");
        var sources = new LinkedHashMap<String, DataSource>();
        sources.put("A", shared);
        sources.put("B", shared);
        Object tc = newTenantConnections(newRuntime(sources), Set.of("A", "B"));

        dslFor(tc, "A");
        dslFor(tc, "B");
        dslFor(tc, "A");

        assertThat(events.stream().filter(e -> e.equals("getConnection:shared")).count())
            .as("session identity is per (connection, tenant): one pin per key, reused within the key")
            .isEqualTo(2);
        assertThat(RecordingHookFixture.MOUNTED_TENANTS).containsExactly(Optional.of("A"), Optional.of("B"));
        releaseAll(tc);
    }

    // --- driving helpers -------------------------------------------------------------------------

    private Object newRuntime(Map<String, DataSource> tenantSources) throws Throwable {
        return runtimeClass.getConstructor(DataSource.class, Map.class, SQLDialect.class, int.class, Duration.class)
            .newInstance(fakeDataSource("default"), new LinkedHashMap<>(tenantSources), SQLDialect.POSTGRES, 2,
                Duration.ofSeconds(10));
    }

    private Object newTenantConnections(Object runtime, Set<String> tenants) throws Throwable {
        return tenantConnectionsClass.getConstructor(runtimeClass, commitPolicyClass, Set.class, String.class)
            .newInstance(runtime, commitPolicyCommit, tenants, "{}");
    }

    private Object dslFor(Object tc, Object key) throws Throwable {
        return invoke(() -> tenantConnectionsClass.getMethod("dslFor", String.class).invoke(tc, key));
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
                case "getAutoCommit" -> true;
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
