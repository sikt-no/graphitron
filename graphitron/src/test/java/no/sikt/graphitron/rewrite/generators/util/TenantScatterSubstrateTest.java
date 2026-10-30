package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.session.SessionIdentityFixtures;
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

    /**
     * The join deadline for a case where one worker is held past it. Every case that holds a worker
     * also needs that worker to have pinned its connection, and enlisted on any active field, before
     * the deadline expires, which a worker started late on a loaded build agent can miss; the held
     * worker is the only one that waits it out, so a generous deadline costs little.
     */
    private static final Duration STRAGGLER_DEADLINE = Duration.ofSeconds(5);

    private static EmittedCodeHarness harness;
    private static Class<?> runtimeClass;
    private static Class<?> tenantConnectionsClass;
    private static Class<?> commitPolicyClass;
    private static Object commitPolicyCommit;
    private static Class<?> successClass;
    private static Class<?> failedClass;
    private static Class<?> timedOutClass;
    private static Class<?> providerClass;
    private static Class<?> nestedScopeFailedClass;

    private final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
    /** Labels whose fake connection fails {@code commit()}, after recording it. */
    private final java.util.Set<String> failCommitFor = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Labels whose probe statement fails as an aborted PostgreSQL transaction does. */
    private final java.util.Set<String> failProbeFor = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Labels whose commit fails with an unchecked exception, as a pool wrapper's might. */
    private final java.util.Set<String> uncheckedCommitFor = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Labels whose {@code setAutoCommit(false)} fails, after recording it. */
    private final java.util.Set<String> failOpenFor = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Labels whose {@code setAutoCommit(false)} blocks until the latch is released, after recording it. */
    private final Map<String, CountDownLatch> stallOpenFor = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeAll
    static void compile() {
        var hooks = SessionIdentityFixtures.recording();
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks, ClassName.get(String.class),
                TenantColumnFixtures.STRING_KEY_COLUMN)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, ClassName.get(String.class), hooks)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        // The tenant-agreement guard raises the client-error type, which a real build always
        // emits beside the runtime.
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.generate()) {
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
        tenantConnectionsClass = harness.load(SCHEMA_PACKAGE + ".TenantConnections");
        successClass = harness.load(SCHEMA_PACKAGE + ".TenantConnections$Outcome$Success");
        failedClass = harness.load(SCHEMA_PACKAGE + ".TenantConnections$Outcome$Failed");
        timedOutClass = harness.load(SCHEMA_PACKAGE + ".TenantConnections$Outcome$TimedOut");
        providerClass = harness.load(SCHEMA_PACKAGE + ".GraphitronTransactionProvider");
        nestedScopeFailedClass = harness.load(SCHEMA_PACKAGE + ".TenantConnections$NestedScopeFailedException");
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
        failCommitFor.clear();
        failProbeFor.clear();
        uncheckedCommitFor.clear();
        failOpenFor.clear();
        stallOpenFor.clear();
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
        Object runtime = newRuntime(sources("A", "B"), 4, STRAGGLER_DEADLINE);
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
        assertThat(events)
            .as("per-tenant session identity mounted once per acquisition, also under concurrency")
            .contains("connect:A", "connect:B", "connect:C");
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
        Object runtime = newRuntime(sources("A", "B"), 4, STRAGGLER_DEADLINE);
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

    @Test
    void interruptedJoin_quarantinesKeysLikeATimeout_soReleaseAbortsNotCloses() throws Throwable {
        Object runtime = newRuntime(sources("A", "B"), 4, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);
        var hold = new CountDownLatch(1);
        var pinned = new CountDownLatch(2);
        var outcomes = new java.util.concurrent.atomic.AtomicReference<List<?>>();
        var scatterFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        Thread dispatch = new Thread(() -> {
            try {
                // Both workers hold, so neither future is complete when the interrupt lands:
                // the first get throws on the interrupt, and the re-set flag makes the second
                // get throw immediately too (a completed future would return its value instead).
                outcomes.set(scatter(tc, List.of("A", "B"), dsl -> {
                    pinned.countDown();
                    return awaitThen(hold, "late");
                }));
            } catch (Throwable t) {
                scatterFailure.set(t);
            }
        });
        dispatch.start();
        // Interrupt only once both workers are parked in their per-tenant body. A worker reaches
        // the body only after entryFor has returned, which is after its quarantine post-check, so
        // the self-abort path is unreachable from here on and releaseAll is the only remaining
        // aborter. (The workers themselves are never interrupted; the interrupt lands in the join,
        // since nothing stops a JDBC call safely.)
        assertThat(pinned.await(5, TimeUnit.SECONDS)).as("both workers reach their body").isTrue();
        dispatch.interrupt();
        dispatch.join(5_000);

        assertThat(dispatch.isAlive()).isFalse();
        assertThat(scatterFailure.get()).as("the join reports outcomes, it does not throw").isNull();
        assertThat(outcomes.get()).hasSize(2);
        assertThat(outcomes.get()).allSatisfy(o -> assertThat(o).isInstanceOf(failedClass));

        // Both keys are quarantined: neither is ever reused, and release aborts rather than
        // closes under a possibly-live worker.
        assertThatThrownBy(() -> dslFor(tc, "A"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("never reused");
        events.clear();
        releaseAll(tc);
        assertThat(events)
            .contains("abort:A", "abort:B")
            .doesNotContain("disconnect:A", "close:A", "disconnect:B", "close:B");
        hold.countDown();
    }

    @Test
    void rejectedExecutionMidSubmit_quarantinesAlreadySubmittedKeys_andPropagates() throws Throwable {
        // A consumer-supplied executor that accepts the first worker and rejects the second: the
        // first worker may already be running, so its key must be quarantined before the
        // rejection propagates; the never-submitted key has no worker and stays usable.
        var delegate = java.util.concurrent.Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "test-fanout");
            thread.setDaemon(true);
            return thread;
        });
        var submits = new AtomicInteger();
        var pinned = new CountDownLatch(1);
        Executor rejecting = task -> {
            if (submits.incrementAndGet() > 1) {
                // Hold the rejection until worker A is parked in its per-tenant body: it reaches
                // the body only after entryFor has returned, which is after its quarantine
                // post-check, so the quarantine below cannot trigger a self-abort. This await runs
                // on the submitting thread while the accepted worker runs on the delegate pool, so
                // it cannot self-block.
                try {
                    if (!pinned.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("worker A never reached its per-tenant body");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                throw new java.util.concurrent.RejectedExecutionException("saturated");
            }
            delegate.execute(task);
        };
        Object runtime = runtimeClass
            .getConstructor(DataSource.class, Map.class, SQLDialect.class, Executor.class, Duration.class)
            .newInstance(fakeDataSource("default", false), new LinkedHashMap<>(sources("A", "B")),
                SQLDialect.POSTGRES, rejecting, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);
        var hold = new CountDownLatch(1);

        assertThatThrownBy(() -> scatter(tc, List.of("A", "B"), dsl -> {
            pinned.countDown();
            return awaitThen(hold, "late");
        }))
            .as("the fanned field fails as one unit; the rejection is not swallowed")
            .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        assertThatThrownBy(() -> dslFor(tc, "A"))
            .as("the submitted key's worker may be running: quarantined")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("never reused");
        dslFor(tc, "B"); // never submitted, no worker: stays usable

        events.clear();
        releaseAll(tc);
        assertThat(events)
            .as("the submitted key aborts (worker possibly live); the pinned-but-unsubmitted key releases")
            .contains("abort:A", "disconnect:B", "close:B")
            .doesNotContain("disconnect:A", "close:A");
        hold.countDown();
    }

    // --- driving helpers -------------------------------------------------------------------------

    // --- a mutation field's transaction across scatter workers -----------------------------------

    @Test
    void mutationField_aTimedOutTenantsScope_isNeverEnded_andFailsTheField() throws Throwable {
        Object runtime = newRuntime(sources("A", "B"), 4, STRAGGLER_DEADLINE);
        Object tc = newTenantConnections(runtime);
        var hold = new CountDownLatch(1);
        beginField(tc, "m");

        // Each worker enlists on the field before its unit of work runs, so A's connection holds an
        // open field transaction when its worker misses the deadline.
        List<?> outcomes = scatter(tc, List.of("A", "B"), dsl ->
            "A-conn".equals(connectionLabel(dsl)) ? awaitThen(hold, "late") : "ok");
        assertThat(outcomes.get(0)).isInstanceOf(timedOutClass);

        events.clear();
        Throwable cause = settleField(tc, false);

        assertThat(cause)
            .as("settling discovered the failure, so it is reported at the field")
            .hasMessageContaining("Tenant 'A' timed out");
        assertThat(events)
            .as("the field fails, so the responsive tenant rolls back; the straggler's connection is"
                + " never touched while its worker may still run on it")
            .contains("rollback:B", "setAutoCommit:B:true")
            .doesNotContain("commit:B", "rollback:A", "commit:A", "setAutoCommit:A:true");

        hold.countDown();
        events.clear();
        releaseAll(tc);
        assertThat(events).as("the abort discards A's transaction with its connection").contains("abort:A");
    }

    @Test
    void mutationField_thatFailed_rollsBackEveryConnectionItTouched() throws Throwable {
        Object runtime = newRuntime(sources("A", "B"), 4, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);
        beginField(tc, "m");
        dslFor(tc, "A");
        dslFor(tc, "B");

        events.clear();
        assertThat(settleField(tc, true))
            .as("the field had already failed, so settling reports nothing of its own")
            .isNull();
        assertThat(events)
            .contains("rollback:A", "rollback:B")
            .doesNotContain("commit:A", "commit:B", "probe:A", "probe:B");
        releaseAll(tc);
    }

    @Test
    void mutationField_aFailedCommitOnTheSecondConnection_keepsTheFirstsCommit_andFailsTheField() throws Throwable {
        Object runtime = newRuntime(sources("A", "B", "C"), 4, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);
        beginField(tc, "m");
        dslFor(tc, "A");
        dslFor(tc, "B");
        dslFor(tc, "C");
        failCommitFor.add("B");

        events.clear();
        Throwable cause = settleField(tc, false);

        assertThat(cause).isInstanceOf(SQLException.class).hasMessageContaining("deferred");
        assertThat(events)
            .as("every connection is probed before any of them commits, then they commit in the order"
                + " opened: A commits, B's commit fails and rolls back, and C, after the field failed,"
                + " rolls back")
            .containsSubsequence("probe:A", "probe:B", "probe:C", "commit:A", "commit:B", "rollback:B", "rollback:C")
            .doesNotContain("rollback:A", "commit:C");
        releaseAll(tc);
    }

    @Test
    void mutationField_aStragglerAfterItsFieldSettled_isRefused_andWritesNothing() throws Throwable {
        Object runtime = newRuntime(sources("A", "B"), 4, STRAGGLER_DEADLINE);
        Object tc = newTenantConnections(runtime);
        var hold = new CountDownLatch(1);
        var workerDone = new CountDownLatch(1);
        var straggler = new java.util.concurrent.atomic.AtomicReference<Object>();
        beginField(tc, "first");

        List<?> outcomes = scatter(tc, List.of("A"), dsl -> {
            awaitThen(hold, null);
            try {
                // A straggler that resumes after its field settled and acquires another key.
                return dslFor(tc, "B");
            } catch (Throwable e) {
                straggler.set(e);
                return e;
            } finally {
                workerDone.countDown();
            }
        });
        assertThat(outcomes.get(0)).isInstanceOf(timedOutClass);
        settleField(tc, false);
        beginField(tc, "second");

        events.clear();
        hold.countDown();
        assertThat(workerDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat((Throwable) straggler.get())
            .as("the acquisition is refused, so the straggler can write nothing")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("has settled");
        assertThat(events)
            .as("B is pinned, but the settled first field opens nothing on it, and the second field"
                + " never sees the straggler")
            .contains("getConnection:B")
            .doesNotContain("setAutoCommit:B:false");

        events.clear();
        assertThat(settleField(tc, false)).as("the second field has nothing to fail for").isNull();
        assertThat(events).doesNotContain("commit:B", "rollback:B");
        releaseAll(tc);
    }

    // --- phase 2: nested scopes join, and settle scans before it commits ------------------------------

    @Test
    void mutationField_aFailedConsumerScope_commitsNoConnection_andReportsTheNestedFailure() throws Throwable {
        Object runtime = newRuntime(sources("A", "B"), 4, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);
        beginField(tc, "m");
        var a = (org.jooq.DSLContext) dslFor(tc, "A");
        dslFor(tc, "B");
        var boom = new IllegalStateException("boom");
        try {
            a.transactionResult(tx -> { throw boom; });
        } catch (IllegalStateException caught) {
            // the consumer's code decides the nested failure is not fatal
        }

        events.clear();
        Throwable report = settleField(tc, false);

        assertThat(report).as("reported even though nothing else failed the field")
            .isInstanceOf(nestedScopeFailedClass).hasCause(boom);
        assertThat(events).contains("rollback:A", "rollback:B").doesNotContain("commit:A", "commit:B");
        releaseAll(tc);
    }

    @Test
    void mutationField_aFailedOwnScope_rollsTheFieldBack_butAddsNoNestedFailureReport() throws Throwable {
        Object runtime = newRuntime(sources("A"), 4, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);
        beginField(tc, "m");
        var a = (org.jooq.DSLContext) dslFor(tc, "A");
        var owned = (org.jooq.DSLContext) providerClass.getMethod("owned", org.jooq.DSLContext.class).invoke(null, a);
        try {
            owned.transactionResult(tx -> { throw new IllegalStateException("fetcher failed"); });
        } catch (IllegalStateException caught) {
            // propagates to the router in a real field, which marks the field failed
        }

        events.clear();
        Throwable report = settleField(tc, false);

        assertThat(report).as("the router's error is the report; no second, misleading one").isNull();
        assertThat(events).contains("rollback:A").doesNotContain("commit:A");
        releaseAll(tc);
    }

    @Test
    void mutationField_aProbeThatFailsOnTheSecondConnection_leavesTheFirstUncommitted() throws Throwable {
        Object runtime = newRuntime(sources("A", "B"), 4, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);
        beginField(tc, "m");
        dslFor(tc, "A");
        dslFor(tc, "B");
        failProbeFor.add("B");

        events.clear();
        Throwable cause = settleField(tc, false);

        assertThat(cause).isInstanceOf(SQLException.class).hasMessageContaining("aborted");
        assertThat(events).contains("probe:A", "probe:B", "rollback:A", "rollback:B")
            .doesNotContain("commit:A", "commit:B");
        releaseAll(tc);
    }

    @Test
    void mutationField_anUncheckedFailureSettlingOneConnection_failsTheField_andTheOthersStillSettle() throws Throwable {
        Object runtime = newRuntime(sources("A", "B"), 4, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);
        beginField(tc, "m");
        dslFor(tc, "A");
        dslFor(tc, "B");
        uncheckedCommitFor.add("A");

        events.clear();
        Throwable cause = settleField(tc, false);

        assertThat(cause).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("pool wrapper");
        assertThat(events).as("A's commit failed, so A and B roll back; B is not left unsettled")
            .contains("commit:A", "rollback:A", "rollback:B").doesNotContain("commit:B");
        releaseAll(tc);
    }

    @Test
    void mutationField_aWorkerWhoseOpenStalls_blocksNeitherAnotherWorkersEnlistNorTheSettle() throws Throwable {
        Object runtime = newRuntime(sources("A", "B"), 4, Duration.ofMillis(400));
        Object tc = newTenantConnections(runtime);
        var stall = new CountDownLatch(1);
        stallOpenFor.put("A", stall);
        beginField(tc, "m");

        List<?> outcomes = scatter(tc, List.of("A", "B"), dsl -> dsl);

        assertThat(outcomes.stream().map(o -> o.getClass().getName()).toList())
            .as("B enlisted and finished while A's open was stalled; A missed the deadline")
            .containsExactly(timedOutClass.getName(), successClass.getName());
        events.clear();
        Throwable cause = settleField(tc, false);

        assertThat(cause).as("settle returned without waiting for the stalled open")
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("still opening");
        assertThat(events).contains("rollback:B").doesNotContain("commit:B", "commit:A", "rollback:A");
        stall.countDown();
        releaseAll(tc);
    }

    @Test
    void mutationField_aSecondAcquirerOfAKey_waitsForTheOpen_andFailsWhenTheOpenFailed() throws Throwable {
        Object runtime = newRuntime(sources("A"), 4, Duration.ofSeconds(10));
        Object tc = newTenantConnections(runtime);
        var stall = new CountDownLatch(1);
        stallOpenFor.put("A", stall);
        failOpenFor.add("A");
        beginField(tc, "m");
        var results = new java.util.concurrent.ConcurrentLinkedQueue<Object>();
        var done = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    results.add(dslFor(tc, "A"));
                } catch (Throwable e) {
                    results.add(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        assertThat(done.await(300, TimeUnit.MILLISECONDS))
            .as("neither acquirer is handed a context while the open is still running").isFalse();
        stall.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(results).as("the waiter fails the way the first acquirer did")
            .hasSize(2).allSatisfy(r -> assertThat(r).isInstanceOf(SQLException.class));
        settleField(tc, false);
        releaseAll(tc);
    }

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
        // The request tenant set admits every hosted tenant: these proofs are about concurrency,
        // and authorization has its own harness test.
        Object hosted = runtimeClass.getMethod("tenantKeys").invoke(runtime);
        Class<?> requestTenantsClass = harness.load(
            SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.REQUEST_TENANTS_CLASS_NAME);
        Object requestTenants = requestTenantsClass.getMethod("of", java.util.Collection.class)
            .invoke(null, hosted);
        Class<?> sessionState = harness.load(SCHEMA_PACKAGE + ".SessionState");
        return tenantConnectionsClass.getConstructor(runtimeClass, commitPolicyClass, requestTenantsClass, sessionState)
            .newInstance(runtime, commitPolicyCommit, requestTenants,
                RecordingSessionState.create(harness, SCHEMA_PACKAGE, "{}"));
    }

    private List<?> scatter(Object tc, Collection<String> keys, Function<Object, Object> perTenant) throws Throwable {
        return (List<?>) invoke(() -> tenantConnectionsClass
            .getMethod("scatter", Collection.class, Function.class)
            .invoke(tc, keys, perTenant));
    }

    private void beginField(Object tc, String resultKey) throws Throwable {
        invoke(() -> tenantConnectionsClass.getMethod("beginField", List.class).invoke(tc, List.of(resultKey)));
    }

    private Throwable settleField(Object tc, boolean failed) throws Throwable {
        return (Throwable) invoke(() -> tenantConnectionsClass.getMethod("settleField", boolean.class).invoke(tc, failed));
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
                    // The connect event fires mid-pin, before entryFor's quarantine post-check:
                    // it is not a worker-is-settled signal, so never synchronize a test on it.
                    events.add((disconnect ? "disconnect:" : "connect:") + label);
                    yield fakePreparedStatement(disconnect && failDisconnect);
                }
                // A mutation field's transaction on this connection, as the carrier opens and ends it.
                case "setAutoCommit" -> {
                    events.add("setAutoCommit:" + label + ":" + args[0]);
                    if (Boolean.FALSE.equals(args[0])) {
                        var stall = stallOpenFor.get(label);
                        if (stall != null) stall.await();
                        if (failOpenFor.contains(label)) throw new SQLException("autocommit refused", "08003");
                    }
                    yield null;
                }
                case "commit" -> {
                    events.add("commit:" + label);
                    if (uncheckedCommitFor.contains(label)) throw new UnsupportedOperationException("pool wrapper failed");
                    if (failCommitFor.contains(label)) throw new SQLException("deferred constraint violated", "23503");
                    yield null;
                }
                case "createStatement" -> {
                    events.add("probe:" + label);
                    yield fakeStatement(label);
                }
                case "rollback" -> { events.add("rollback:" + label); yield null; }
                case "close" -> { events.add("close:" + label); yield null; }
                case "abort" -> { events.add("abort:" + label); yield null; }
                case "toString" -> label + "-conn";
                default -> objectMethodOrDefault(proxy, method, args, label);
            });
    }

    private java.sql.Statement fakeStatement(String label) {
        return (java.sql.Statement) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[]{java.sql.Statement.class},
            (proxy, method, args) -> {
                if (method.getName().equals("execute") && failProbeFor.contains(label)) {
                    throw new SQLException("current transaction is aborted", "25P02");
                }
                return objectMethodOrDefault(proxy, method, args, "fakeStatement");
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
