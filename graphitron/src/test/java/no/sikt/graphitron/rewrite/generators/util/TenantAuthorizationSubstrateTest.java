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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct-carrier proofs for routed acquisition over the real emitted multi-tenant runtime and fake
 * JDBC (the {@link TenantScatterSubstrateTest} pattern). Which tenants a request may reach is the
 * source's to say: a key it refuses with {@code KeyNotPermitted} answers as a client error carrying
 * the refusal's message, and the refusal is remembered, so the source is asked once per key;
 * {@code dslForIfPermitted} answers such a key with {@code null} instead. The fan-out domain is the
 * source's {@code tenants()}, read once per request, in its order. The source's mount receives the
 * key it is mounting for, whose tenant is {@code Optional.empty()} for the empty key every global
 * read and write runs on, one mount per key even when keys share a {@code DataSource}.
 */
@UnitTier
class TenantAuthorizationSubstrateTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> runtimeClass;
    private static Class<?> ownedConnectionsClass;
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
    void aKeyTheSourceRefuses_answersWithTheSourcesRefusalAsAClientError_beforeAnyConnectionIsTaken()
            throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Map.of("A", fakeDataSource("A"))));

        assertThatThrownBy(() -> dslFor(tc, "Z"))
            .as("the source's KeyNotPermitted reaches the field as a client error carrying its message")
            .isInstanceOf(clientExceptionClass)
            .hasMessage("Tenant 'Z' is not hosted here.");
        assertThat(events).as("no connection to a refused tenant is ever opened").isEmpty();
        assertThat(RecordingSource.mountedTenants(harness)).isEmpty();
        releaseAll(tc);
    }

    @Test
    void aRefusedKey_isRemembered_soTheSourceIsAskedOnce() throws Throwable {
        var asks = new java.util.concurrent.atomic.AtomicInteger();
        DataSource a = fakeDataSource("A");
        Object source = RecordingSource.create(harness, SCHEMA_PACKAGE, "{}", key -> {
            if (RecordingSource.labelOf(key).equals("Z")) {
                asks.incrementAndGet();
            }
            return RecordingSource.labelOf(key).equals("A") ? a : refuse("not yours");
        }, List.of("A", "Z"));
        Object tc = newOwnedConnections(new Hosted(newRuntimeOnly(), source));

        assertThatThrownBy(() -> dslFor(tc, "Z")).isInstanceOf(clientExceptionClass).hasMessage("not yours");
        assertThatThrownBy(() -> dslFor(tc, "Z")).isInstanceOf(clientExceptionClass).hasMessage("not yours");
        dslFor(tc, "A");

        assertThat(asks).hasValue(1);
        assertThat(RecordingSource.mountedTenants(harness)).containsExactly(Optional.of("A"));
        releaseAll(tc);
    }

    @Test
    void mountReceivesTheRoutedKey_andEmptyForTheEmptyKey() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Map.of("A", fakeDataSource("A"))));

        dslFor(tc, "A");
        invoke(() -> ownedConnectionsClass.getMethod("dslDefault").invoke(tc));

        assertThat(RecordingSource.mountedTenants(harness))
            .containsExactly(Optional.of("A"), Optional.empty());
        releaseAll(tc);
    }

    @Test
    void dslDefault_runsOnThePoolTheSourcePicksForTheEmptyKey() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Map.of("A", fakeDataSource("A"))));

        invoke(() -> ownedConnectionsClass.getMethod("dslDefault").invoke(tc));

        assertThat(events).containsExactly("getConnection:default");
        assertThat(RecordingSource.mountedTenants(harness)).containsExactly(Optional.empty());
        releaseAll(tc);
    }

    @Test
    void keysSharingOneDataSource_getOneConnectionAndOneMountEach_withTheirOwnKey() throws Throwable {
        DataSource shared = fakeDataSource("shared");
        var sources = new LinkedHashMap<String, DataSource>();
        sources.put("A", shared);
        sources.put("B", shared);
        Object tc = newOwnedConnections(newRuntime(sources));

        dslFor(tc, "A");
        dslFor(tc, "B");
        dslFor(tc, "A");

        assertThat(events.stream().filter(e -> e.equals("getConnection:shared")).count())
            .as("session identity is per (connection, tenant): one pin per key, reused within the key")
            .isEqualTo(2);
        assertThat(RecordingSource.mountedTenants(harness)).containsExactly(Optional.of("A"), Optional.of("B"));
        releaseAll(tc);
    }

    // --- dslForIfPermitted: the entity and node dispatch's acquisition ---------------------------

    @Test
    void dslForIfPermitted_answersARefusedKeyWithNull_rememberedAcrossCalls() throws Throwable {
        var asks = new java.util.concurrent.atomic.AtomicInteger();
        DataSource a = fakeDataSource("A");
        Object source = RecordingSource.create(harness, SCHEMA_PACKAGE, "{}", key -> {
            if (RecordingSource.labelOf(key).equals("Z")) {
                asks.incrementAndGet();
                return refuse("not yours");
            }
            return a;
        }, List.of("A"));
        Object tc = newOwnedConnections(new Hosted(newRuntimeOnly(), source));
        var env = envOver(tc);

        assertThat(dslForIfPermitted(env, "Z")).isNull();
        assertThat(dslForIfPermitted(env, "Z")).isNull();
        assertThat(asks).as("the refusal is remembered").hasValue(1);
        assertThatThrownBy(() -> dslFor(tc, "Z"))
            .as("a routed field asking the same key still answers with the error")
            .isInstanceOf(clientExceptionClass).hasMessage("not yours");
        assertThat(asks).hasValue(1);

        Object permitted = dslForIfPermitted(env, "A");
        assertThat(permitted).isNotNull().isSameAs(dslFor(tc, "A"));
        releaseAll(tc);
    }

    // --- the fan-out domain ----------------------------------------------------------------------

    @Test
    void fanOutDomain_isTheSourcesTenants_inItsOrder_readOncePerRequest() throws Throwable {
        Hosted hosted = newRuntime(Map.of("A", fakeDataSource("A"), "B", fakeDataSource("B")));
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        Object delegate = hosted.source();
        Class<?> sourceInterface = harness.load(SCHEMA_PACKAGE + ".ConnectionSource");
        Object counting = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[]{sourceInterface},
            (proxy, method, args) -> switch (method.getName()) {
                case "tenants" -> {
                    reads.incrementAndGet();
                    yield new java.util.LinkedHashSet<>(List.of("B", "A"));
                }
                case "acquire" -> method.invoke(delegate, args);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> "CountingSource";
            });
        Object tc = newOwnedConnections(new Hosted(hosted.runtime(), counting));
        var env = envOver(tc);

        assertThat(fanOutDomain(env)).containsExactly("B", "A");
        assertThat(fanOutDomain(env)).containsExactly("B", "A");
        assertThat(reads).as("tenants() is read once per request").hasValue(1);
        releaseAll(tc);
    }

    // --- driving helpers -------------------------------------------------------------------------

    /** A runtime, paired with the request source over {@code tenantPools} and a default pool. */
    private Hosted newRuntime(Map<String, DataSource> tenantPools) throws Throwable {
        var pools = new LinkedHashMap<String, DataSource>(tenantPools);
        pools.put(RecordingSource.DEFAULT, fakeDataSource(RecordingSource.DEFAULT));
        return new Hosted(newRuntimeOnly(), RecordingSource.create(harness, SCHEMA_PACKAGE, "{}", pools));
    }

    private static Object newRuntimeOnly() throws Throwable {
        return runtimeClass.getConstructor(SQLDialect.class, Settings.class, int.class, Duration.class)
            .newInstance(SQLDialect.POSTGRES, new Settings(), 2, Duration.ofSeconds(10));
    }

    private record Hosted(Object runtime, Object source) {}

    private Object newOwnedConnections(Hosted hosted) throws Throwable {
        Class<?> sourceClass = harness.load(SCHEMA_PACKAGE + ".ConnectionSource");
        return ownedConnectionsClass.getConstructor(runtimeClass, commitPolicyClass, sourceClass)
            .newInstance(hosted.runtime(), commitPolicyCommit, hosted.source());
    }

    /** Throws the generated {@code KeyNotPermitted} with {@code message}, from inside a pool function. */
    private static DataSource refuse(String message) {
        try {
            throw (RuntimeException) harness.load(SCHEMA_PACKAGE + ".KeyNotPermitted")
                .getConstructor(String.class).newInstance(message);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A field's environment whose GraphQL context holds {@code tc}, as the instrumentation publishes it. */
    private static graphql.schema.DataFetchingEnvironment envOver(Object tc) {
        return graphql.schema.DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(graphql.GraphQLContext.of(Map.of(ownedConnectionsClass, tc)))
            .build();
    }

    private static Object dslForIfPermitted(graphql.schema.DataFetchingEnvironment env, String tenant)
            throws Throwable {
        return invoke(() -> ownedConnectionsClass
            .getMethod("dslForIfPermitted", graphql.schema.DataFetchingEnvironment.class, String.class)
            .invoke(null, env, tenant));
    }

    private static List<Object> fanOutDomain(graphql.schema.DataFetchingEnvironment env) throws Throwable {
        @SuppressWarnings("unchecked")
        var domain = (List<Object>) invoke(() -> ownedConnectionsClass
            .getMethod("fanOutDomain", graphql.schema.DataFetchingEnvironment.class)
            .invoke(null, env));
        return domain;
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
