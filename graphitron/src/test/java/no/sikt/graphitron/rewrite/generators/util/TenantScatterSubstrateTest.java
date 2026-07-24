package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.Variable;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct-carrier concurrency proofs for the {@code TenantConnections.scatter} substrate: compiles
 * the real emitted multi-tenant runtime and drives it over fake JDBC (the
 * {@link TenantConnectionsGeneratorTest} pattern), so every concurrency invariant has a named
 * enforcer here before any fan-out emitter rides on the helper. Covered: the concurrency cap, the
 * join deadline, failure isolation, outcome ordering, per-key single acquisition under contention,
 * the re-entrancy guard, and the straggler contract (a timed-out worker's connection is aborted,
 * never closed under a live statement, never reused, and its eventual completion lands harmlessly).
 */
@UnitTier
class TenantScatterSubstrateTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> runtimeClass;
    private static Class<?> tenantConnectionsClass;
    private static Class<?> commitPolicyClass;
    private static Object commitPolicyCommit;
    private static Class<?> successClass;
    private static Class<?> failedClass;
    private static Class<?> timedOutClass;

    private final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void compile() {
        var config = SessionStateConfig.from(null, null, List.of(new Variable("app.uid", "sub")));
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE, config, ClassName.get(String.class))) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, true)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compile(units);
        runtimeClass = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
        tenantConnectionsClass = harness.load(SCHEMA_PACKAGE + ".TenantConnections");
        successClass = harness.load(SCHEMA_PACKAGE + ".TenantConnections$Outcome$Success");
        failedClass = harness.load(SCHEMA_PACKAGE + ".TenantConnections$Outcome$Failed");
        timedOutClass = harness.load(SCHEMA_PACKAGE + ".TenantConnections$Outcome$TimedOut");
        Class<?> providerClass = harness.load(SCHEMA_PACKAGE + ".GraphitronTransactionProvider");
        commitPolicyClass = java.util.Arrays.stream(providerClass.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("CommitPolicy"))
            .findFirst().orElseThrow();
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object commit = Enum.valueOf((Class) commitPolicyClass, "COMMIT");
        commitPolicyCommit = commit;
        // Warm jOOQ's static initialization (first-ever DSL.using in a JVM costs hundreds of ms):
        // the deadline tests below hold one worker past a short join deadline and need the OTHER
        // worker's dslFor to be fast, not paying one-time class-init inside the measured window.
        org.jooq.impl.DSL.using(SQLDialect.POSTGRES);
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
    void scatter_runsWorkersInParallel_boundedByTheConcurrencyCap() throws Throwable {
        Object runtime = newRuntime(sources("A", "B", "C", "D"), 2, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);

        // With a cap of 2 and four keys, exactly two workers can be in flight: a barrier of two
        // proves genuine parallelism (a serial run would deadlock the barrier, tripping its
        // timeout into a Failed outcome), and the active counter proves the bound holds.
        var barrier = new CyclicBarrier(2);
        var active = new AtomicInteger();
        var maxActive = new AtomicInteger();
        Function<Object, Object> perTenant = dsl -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                active.decrementAndGet();
            }
            return "ok";
        };

        List<?> outcomes = scatter(tc, List.of("A", "B", "C", "D"), perTenant);

        assertThat(outcomes).hasSize(4).allSatisfy(o -> assertThat(o).isInstanceOf(successClass));
        assertThat(maxActive.get())
            .as("two workers genuinely overlap (parallelism), and never more than the cap")
            .isEqualTo(2);
        releaseAll(tc);
    }

    @Test
    void scatter_joinDeadline_yieldsTimedOutForTheStragglerAndSuccessForTheRest() throws Throwable {
        Object runtime = newRuntime(sources("A", "B"), 4, Duration.ofMillis(800));
        Object tc = newTenantConnections(runtime);
        var hold = new CountDownLatch(1);

        List<?> outcomes = scatter(tc, List.of("A", "B"), dsl ->
            "A-conn".equals(connectionLabel(dsl)) ? awaitThen(hold, "late") : "ok");

        assertThat(outcomes.get(0)).as("the held worker misses the deadline").isInstanceOf(timedOutClass);
        assertThat(outcomes.get(1)).isInstanceOf(successClass);
        assertThat(key(outcomes.get(0))).isEqualTo("A");
        hold.countDown();
        releaseAll(tc);
    }

    @Test
    void scatter_failureIsolation_oneThrowingWorkerCarriesItsCauseWhileSiblingsSucceed() throws Throwable {
        Object runtime = newRuntime(sources("A", "B", "C"), 4, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);
        var boom = new IllegalArgumentException("tenant B exploded");

        List<?> outcomes = scatter(tc, List.of("A", "B", "C"), dsl -> {
            if ("B-conn".equals(connectionLabel(dsl))) {
                throw boom;
            }
            return "ok";
        });

        assertThat(outcomes.get(0)).isInstanceOf(successClass);
        assertThat(outcomes.get(1)).isInstanceOf(failedClass);
        assertThat(cause(outcomes.get(1))).as("the cause is carried, never swallowed").isSameAs(boom);
        assertThat(outcomes.get(2)).isInstanceOf(successClass);
        releaseAll(tc);
    }

    @Test
    void scatter_outcomesComeBackInKeyIterationOrder_withValuesAttached() throws Throwable {
        Object runtime = newRuntime(sources("A", "B", "C"), 4, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);

        List<?> outcomes = scatter(tc, List.of("C", "A", "B"), dsl -> connectionLabel(dsl));

        assertThat(outcomes).extracting(TenantScatterSubstrateTest::key)
            .as("outcome order is the keys' iteration order, not completion order")
            .containsExactly("C", "A", "B");
        assertThat(outcomes).extracting(TenantScatterSubstrateTest::value)
            .as("each worker saw its own tenant's connection")
            .containsExactly("C-conn", "A-conn", "B-conn");
        releaseAll(tc);
    }

    @Test
    void dslFor_pinsExactlyOncePerKeyUnderContention() throws Throwable {
        Object runtime = newRuntime(sources("A"), 8, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);

        int contenders = 8;
        var start = new CyclicBarrier(contenders);
        var done = new CountDownLatch(contenders);
        var failures = java.util.Collections.synchronizedList(new ArrayList<Throwable>());
        for (int i = 0; i < contenders; i++) {
            new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    dslFor(tc, "A");
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        assertThat(events.stream().filter(e -> e.equals("getConnection:A")).count())
            .as("many threads racing the same key acquire exactly one pin")
            .isEqualTo(1);
        releaseAll(tc);
    }

    @Test
    void scatter_isNotReentrant_aPerTenantBodyCallingScatterFailsImmediately() throws Throwable {
        Object runtime = newRuntime(sources("A", "B"), 4, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);

        List<?> outcomes = scatter(tc, List.of("A"), dsl -> {
            try {
                return scatter(tc, List.of("B"), inner -> "never");
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        assertThat(outcomes.get(0)).isInstanceOf(failedClass);
        Throwable cause = cause(outcomes.get(0));
        while (cause.getCause() != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }
        assertThat(cause)
            .as("the nested scatter throws the guard immediately rather than deadlocking the pool")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not re-entrant");
        assertThat(events).as("the inner scatter never pinned tenant B").doesNotContain("getConnection:B");
        releaseAll(tc);
    }

    @Test
    void straggler_releaseAllAbortsItsConnection_neverReusesIt_andCompletionLandsHarmlessly() throws Throwable {
        Object runtime = newRuntime(sources("A", "B"), 4, Duration.ofMillis(800));
        Object tc = newTenantConnections(runtime);
        var hold = new CountDownLatch(1);
        var workerDone = new CountDownLatch(1);

        List<?> outcomes = scatter(tc, List.of("A", "B"), dsl -> {
            if ("A-conn".equals(connectionLabel(dsl))) {
                try {
                    return awaitThen(hold, "late");
                } finally {
                    workerDone.countDown();
                }
            }
            return "ok";
        });
        assertThat(outcomes.get(0)).isInstanceOf(timedOutClass);

        // The timed-out key's pinned entry is never reused later in the operation.
        assertThatThrownBy(() -> dslFor(tc, "A"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("never reused");

        events.clear();
        releaseAll(tc);
        assertThat(events)
            .as("the settled tenant releases normally; the straggler's connection is aborted, not closed"
                + " under a possibly-live statement, and its identity is never 'unmounted' concurrently")
            .contains("disconnect:B", "close:B", "abort:A")
            .doesNotContain("disconnect:A", "close:A");

        // The straggler's eventual completion neither throws into the void nor pins anything new.
        events.clear();
        hold.countDown();
        assertThat(workerDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).as("no new pin, no release-path activity from the late completion").isEmpty();
    }

    // --- driving helpers -------------------------------------------------------------------------

    private Map<String, DataSource> sources(String... labels) {
        var sources = new LinkedHashMap<String, DataSource>();
        for (String label : labels) {
            sources.put(label, fakeDataSource(label, false));
        }
        return sources;
    }

    private Object newRuntime(Map<String, DataSource> tenantSources, int cap, Duration timeout) throws Throwable {
        DataSource defaultDs = fakeDataSource("default", false);
        return runtimeClass.getConstructor(DataSource.class, Map.class, SQLDialect.class, int.class, Duration.class)
            .newInstance(defaultDs, new LinkedHashMap<>(tenantSources), SQLDialect.POSTGRES, cap, timeout);
    }

    private Object newTenantConnections(Object runtime) throws Throwable {
        return tenantConnectionsClass.getConstructor(runtimeClass, String.class, commitPolicyClass)
            .newInstance(runtime, "{}", commitPolicyCommit);
    }

    private List<?> scatter(Object tc, Collection<String> keys, Function<Object, Object> perTenant) throws Throwable {
        return (List<?>) invoke(() -> tenantConnectionsClass
            .getMethod("scatter", Collection.class, Function.class)
            .invoke(tc, keys, perTenant));
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

    private static Object key(Object outcome) {
        return read(outcome, "key");
    }

    private static Object value(Object outcome) {
        return read(outcome, "value");
    }

    private static Throwable cause(Object outcome) {
        return (Throwable) read(outcome, "cause");
    }

    private static Object read(Object outcome, String accessor) {
        try {
            return outcome.getClass().getMethod(accessor).invoke(outcome);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The tenant label baked into the fake connection a worker's {@code DSLContext} wraps. */
    private static String connectionLabel(Object dsl) {
        // The harness loader is parented to this test's loader, so jOOQ types are shared and the
        // emitted DSLContext casts directly.
        return String.valueOf(((org.jooq.DSLContext) dsl).configuration().connectionProvider().acquire());
    }

    private static Object awaitThen(CountDownLatch latch, Object result) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        return result;
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

    private Connection fakeConnection(String label, boolean failDisconnect) {
        return (Connection) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                // The <variables> hook runs prepareStatement for both connect and disconnect; the disconnect
                // SQL is the one that clears to the empty string. Label the hook phase off the SQL.
                case "prepareStatement" -> {
                    boolean disconnect = ((String) args[0]).contains("'', false");
                    events.add((disconnect ? "disconnect:" : "connect:") + label);
                    yield fakePreparedStatement(disconnect && failDisconnect);
                }
                case "close" -> { events.add("close:" + label); yield null; }
                case "abort" -> { events.add("abort:" + label); yield null; }
                case "toString" -> label + "-conn";
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
