package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import graphql.GraphQLContext;
import graphql.Scalars;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.emit.ConnectionKeyShape;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the connection carrier does after a mutation field with {@code @outbox(on: ERROR)} messages
 * rolls back, on the real emitted multi-tenant runtime over fake JDBC: which connection the
 * registered messages are handed, or that they are handed none, and what they are told the failure
 * was claimed as. The choice reads what the rollback raised, so each case makes a fake connection
 * raise what a driver would; which states pgjdbc actually raises is pinned against PostgreSQL by
 * {@code ServerEndedSessionPinTest} in the example module.
 *
 * <p>Every fake connection is labelled with its tenant, and every JDBC call it sees is recorded as
 * {@code call:label}, so a case reads whether a fresh connection was taken ({@code getConnection}
 * twice) and whether the old one was aborted. The registered messages are a stand-in that records
 * the connection it was handed and what the failure was claimed as.
 */
@UnitTier
class OutboxFailureSettleTest {

    private static final String PACKAGE = "com.example";
    private static final String SCHEMA_PACKAGE = PACKAGE + ".schema";

    private static EmittedCodeHarness harness;
    private static Class<?> runtimeClass;
    private static Class<?> ownedConnectionsClass;
    private static Class<?> commitPolicyClass;
    private static Class<?> fieldRollbackClass;
    private static Class<?> errorRouterClass;
    private static Class<?> mappingClass;
    private static Class<?> databaseMappingClass;

    private final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
    /** Labels whose fake connection fails {@code commit()} as a deferred constraint does. */
    private final java.util.Set<String> failCommitFor = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Labels whose {@code rollback()} raises the mapped exception, for the first connection only. */
    private final Map<String, SQLException> failRollbackFor = new java.util.concurrent.ConcurrentHashMap<>();
    /** Labels whose {@code getConnection} parks on the latch from the second call on: a pool that is exhausted. */
    private final Map<String, CountDownLatch> exhaustedAfterFirst = new java.util.concurrent.ConcurrentHashMap<>();
    /** Labels whose mount statement fails from the second connection on. */
    private final java.util.Set<String> failSecondMountFor = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> connectionsTaken = new java.util.concurrent.ConcurrentHashMap<>();

    /** What the registered failure messages were handed, one entry per run. */
    private final List<Object[]> written = java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void compile() {
        var hooks = SessionIdentityFixtures.recording();
        Map<String, TypeSpec> units = new LinkedHashMap<>();
        for (TypeSpec spec : ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks,
                ConnectionKeyShape.ofTenantKey(ClassName.get(String.class)), TenantColumnFixtures.STRING_KEY_COLUMN,
                false, true)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronTransactionProviderGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, ClassName.get(String.class), hooks)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.generate()) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.generate(PACKAGE)) {
            units.put(SCHEMA_PACKAGE + "." + spec.name(), spec);
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compile(units);
        runtimeClass = harness.load(SCHEMA_PACKAGE + ".GraphitronRuntime");
        ownedConnectionsClass = harness.load(SCHEMA_PACKAGE + ".OwnedConnections");
        fieldRollbackClass = harness.load(SCHEMA_PACKAGE + ".OwnedConnections$FieldRollback");
        errorRouterClass = harness.load(SCHEMA_PACKAGE + ".ErrorRouter");
        mappingClass = harness.load(SCHEMA_PACKAGE + ".ErrorRouter$Mapping");
        databaseMappingClass = harness.load(SCHEMA_PACKAGE + ".ErrorRouter$DatabaseMapping");
        var providerClass = harness.load(SCHEMA_PACKAGE + ".GraphitronTransactionProvider");
        commitPolicyClass = java.util.Arrays.stream(providerClass.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("CommitPolicy"))
            .findFirst().orElseThrow();
        org.jooq.impl.DSL.using(SQLDialect.POSTGRES);
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
        written.clear();
        connectionsTaken.clear();
    }

    // --- the connection the messages are written on ------------------------------------------------

    @Test
    void aFieldThatFailed_writesOnItsOwnConnection_afterItsRollback() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
        beginField(tc);
        var env = env(tc);
        register(env);
        Object own = dslFor(tc, "A");
        markFieldFailed(env, new RuntimeException("redacted"));

        assertThat(settleField(tc, false)).isNull();

        assertThat(written).as("written once").hasSize(1);
        assertThat(written.getFirst()[0]).as("on the field's own connection, its session still mounted").isSameAs(own);
        assertThat(written.getFirst()[1]).as("a redacted failure was claimed as nothing").isNull();
        assertThat(events).as("after the rollback").containsSubsequence("rollback:A", "written");
        assertThat(connectionsTaken.get("A")).as("no second connection").isEqualTo(1);
        releaseAll(tc);
    }

    @Test
    void aFieldThatCommitted_writesNothing() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
        beginField(tc);
        register(env(tc));
        dslFor(tc, "A");

        assertThat(settleField(tc, false)).isNull();

        assertThat(events).contains("commit:A");
        assertThat(written).isEmpty();
        releaseAll(tc);
    }

    @Test
    void aCommitThatFails_rollsBack_andWritesOnTheConnectionItLeft() throws Throwable {
        failCommitFor.add("A");
        try {
            Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
            beginField(tc);
            register(env(tc));
            Object own = dslFor(tc, "A");

            assertThat(settleField(tc, false)).as("the failed commit is the field's failure").isInstanceOf(SQLException.class);

            assertThat(events).containsSubsequence("commit:A", "rollback:A", "written");
            assertThat(written).singleElement().satisfies(w -> assertThat(w[0]).isSameAs(own));
            releaseAll(tc);
        } finally {
            failCommitFor.clear();
        }
    }

    @Test
    void aTimedOutTenant_isAbortedAtSettle_andTheMessagesGoOnAFreshConnection() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
        var hold = new CountDownLatch(1);
        beginField(tc);
        register(env(tc));
        Object own = dslFor(tc, "A");
        // A child's scatter reuses the field's connection for A and its worker misses the deadline.
        List<?> outcomes = scatter(tc, List.of("A"), dsl -> awaitThen(hold, "late"));
        assertThat(outcomes.getFirst().getClass().getSimpleName()).isEqualTo("TimedOut");

        events.clear();
        settleField(tc, false);

        assertThat(events)
            .as("the straggler's connection is aborted at settle, never rolled back under it, and only then"
                + " is a second one taken")
            .containsSubsequence("abort:A", "getConnection:A", "written", "close:A")
            .doesNotContain("rollback:A");
        assertThat(written).singleElement().satisfies(w -> assertThat(w[0]).isNotSameAs(own));
        hold.countDown();
        releaseAll(tc);
    }

    @Test
    void aRollbackThatFailsBecauseTheServerEndedTheSessionOverTheTransaction_writesOnAFreshConnection() throws Throwable {
        failRollbackFor.put("A", new SQLException("terminating connection due to idle-in-transaction timeout", "25P03"));
        try {
            Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
            beginField(tc);
            var env = env(tc);
            register(env);
            Object own = dslFor(tc, "A");
            markFieldFailed(env, new RuntimeException("redacted"));

            settleField(tc, false);

            assertThat(events).containsSubsequence("rollback:A", "getConnection:A", "written", "close:A");
            assertThat(written).singleElement().satisfies(w -> assertThat(w[0]).isNotSameAs(own));
            releaseDeadConnection(tc);
        } finally {
            failRollbackFor.clear();
        }
    }

    @Test
    void aRollbackThatFailsForAReasonOutsideTheTransaction_writesNothing() throws Throwable {
        for (var cause : List.of(
                new SQLException("An I/O error occurred while sending to the backend.", "08006"),
                new SQLException("terminating connection due to administrator command", "57P01"),
                new SQLException("something nobody recognises"))) {
            failRollbackFor.put("A", cause);
            try {
                Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
                beginField(tc);
                var env = env(tc);
                register(env);
                dslFor(tc, "A");
                markFieldFailed(env, new RuntimeException("redacted"));

                settleField(tc, false);

                assertThat(written).as(String.valueOf(cause.getSQLState())).isEmpty();
                assertThat(connectionsTaken.get("A")).as("no fresh connection for " + cause.getSQLState()).isEqualTo(1);
                releaseDeadConnection(tc);
            } finally {
                failRollbackFor.clear();
                written.clear();
                connectionsTaken.clear();
            }
        }
    }

    @Test
    void aFieldThatAcquiredNoConnection_writesNothing() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
        beginField(tc);
        var env = env(tc);
        register(env);
        markFieldFailed(env, new RuntimeException("failed before acquiring"));

        settleField(tc, false);

        assertThat(written).isEmpty();
        releaseAll(tc);
    }

    @Test
    void aFreshConnectionWhoseMountFails_writesNothing() throws Throwable {
        failRollbackFor.put("A", new SQLException("terminating connection due to idle-in-transaction timeout", "25P03"));
        failSecondMountFor.add("A");
        try {
            Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
            beginField(tc);
            var env = env(tc);
            register(env);
            dslFor(tc, "A");
            markFieldFailed(env, new RuntimeException("redacted"));

            settleField(tc, false);

            assertThat(connectionsTaken.get("A")).as("a fresh connection was asked for").isEqualTo(2);
            assertThat(written).as("nothing is written without a mount").isEmpty();
            assertThat(events).as("the half-mounted connection is evicted").containsSubsequence("getConnection:A", "abort:A");
            releaseDeadConnection(tc);
        } finally {
            failRollbackFor.clear();
            failSecondMountFor.clear();
        }
    }

    @Test
    void aFreshConnectionThatDoesNotArriveWithinTheBound_isAbandoned_andALateOneIsEvicted() throws Throwable {
        failRollbackFor.put("A", new SQLException("terminating connection due to idle-in-transaction timeout", "25P03"));
        var pool = new CountDownLatch(1);
        exhaustedAfterFirst.put("A", pool);
        try {
            Object tc = newOwnedConnections(newRuntime(Duration.ofMillis(300)));
            beginField(tc);
            var env = env(tc);
            register(env);
            dslFor(tc, "A");
            markFieldFailed(env, new RuntimeException("redacted"));

            long started = System.nanoTime();
            settleField(tc, false);
            long tookMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(tookMillis).as("settle is held no longer than the bound, give or take").isLessThan(3_000);
            assertThat(written).isEmpty();

            events.clear();
            pool.countDown();
            awaitEvent("abort:A");
            assertThat(written).as("the connection that arrived late is evicted, never written on").isEmpty();
            releaseDeadConnection(tc);
        } finally {
            failRollbackFor.clear();
            exhaustedAfterFirst.clear();
        }
    }

    @Test
    void messagesThatFailToWrite_areLogged_andSettleCarriesOn() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
        beginField(tc);
        var env = env(tc);
        onFieldRollback(env, (dsl, claimed) -> {
            throw new IllegalStateException("the outbox refused it");
        });
        dslFor(tc, "A");
        markFieldFailed(env, new RuntimeException("redacted"));

        assertThat(settleField(tc, false)).as("the field had already failed; the write's failure adds nothing").isNull();
        releaseAll(tc);
    }

    // --- what the messages are told the failure was claimed as -------------------------------------

    @Test
    void aClaimedFailure_handsTheMatchedLink_notTheExceptionTheFetcherCaught() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
        beginField(tc);
        var env = env(tc);
        register(env);
        dslFor(tc, "A");
        var violation = new SQLException("insert violates foreign key", "23503");
        var caught = new RuntimeException("wrapped by the driver's caller", violation);

        assertThat(claim(caught, env)).as("the claim matched below what was thrown").isSameAs(violation);
        settleField(tc, false);

        assertThat(written).singleElement().satisfies(w -> assertThat(w[1]).isSameAs(violation));
        releaseAll(tc);
    }

    @Test
    void theFirstClaimWins() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
        beginField(tc);
        var env = env(tc);
        register(env);
        dslFor(tc, "A");
        var first = new SQLException("first", "23503");
        var second = new SQLException("second", "23503");

        claim(first, env);
        claim(second, env);
        settleField(tc, false);

        assertThat(written).singleElement().satisfies(w -> assertThat(w[1]).isSameAs(first));
        releaseAll(tc);
    }

    @Test
    void aFailureNoMappingClaims_isClaimedAsNothing() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
        beginField(tc);
        var env = env(tc);
        register(env);
        dslFor(tc, "A");

        assertThat(claim(new SQLException("no such state", "22001"), env)).isNull();
        // What the fetcher's catch arm does next with an unclaimed throw: it fails the field.
        markFieldFailed(env, new SQLException("no such state", "22001"));
        settleField(tc, false);

        assertThat(written).singleElement().satisfies(w -> assertThat(w[1]).isNull());
        releaseAll(tc);
    }

    @Test
    void aValidationRefusal_failsTheField_andHandsItsFirstError() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
        beginField(tc);
        var env = env(tc);
        register(env);
        Object own = dslFor(tc, "A");
        var first = graphql.GraphqlErrorBuilder.newError().message("title is too long").build();
        var second = graphql.GraphqlErrorBuilder.newError().message("rating is out of range").build();

        markFieldRefused(env, List.of(first, second));

        assertThat(settleField(tc, false))
            .as("the payload already carries the refusal; settle reports nothing of its own")
            .isNull();
        assertThat(events).as("the refusal rolls the field back").contains("rollback:A").doesNotContain("commit:A");
        assertThat(written).singleElement().satisfies(w -> {
            assertThat(w[0]).as("on the field's own connection").isSameAs(own);
            assertThat(w[1]).as("the first error the refusal produced").isSameAs(first);
        });
        releaseAll(tc);
    }

    @Test
    void aClaimBeforeARefusal_stillWins() throws Throwable {
        Object tc = newOwnedConnections(newRuntime(Duration.ofSeconds(5)));
        beginField(tc);
        var env = env(tc);
        register(env);
        dslFor(tc, "A");
        var violation = new SQLException("insert violates foreign key", "23503");

        claim(violation, env);
        markFieldRefused(env, List.of(graphql.GraphqlErrorBuilder.newError().message("later").build()));
        settleField(tc, false);

        assertThat(written).singleElement().satisfies(w -> assertThat(w[1]).isSameAs(violation));
        releaseAll(tc);
    }

    // --- the classification ------------------------------------------------------------------------

    @Test
    void endedOverTransaction_namesTheStatesThatEndASessionOverItsTransaction() throws Throwable {
        Method classify = ownedConnectionsClass.getMethod("endedOverTransaction", Throwable.class, SQLDialect.class);
        assertThat((boolean) classify.invoke(null, new SQLException("idle", "25P03"), SQLDialect.POSTGRES)).isTrue();
        assertThat((boolean) classify.invoke(null, new SQLException("timeout", "25P04"), SQLDialect.POSTGRES)).isTrue();
        assertThat((boolean) classify.invoke(null,
                new RuntimeException("wrapped", new SQLException("idle", "25P03")), SQLDialect.POSTGRES))
            .as("anywhere on the cause chain").isTrue();
        var chained = new SQLException("batch failed", "08006");
        chained.setNextException(new SQLException("idle", "25P03"));
        assertThat((boolean) classify.invoke(null, chained, SQLDialect.POSTGRES))
            .as("or among a SQLException's chained exceptions").isTrue();
        for (var state : new String[] {"08006", "08003", "57P01", "57P02", "57P03", "25P02", "40001", null}) {
            assertThat((boolean) classify.invoke(null, new SQLException("x", state), SQLDialect.POSTGRES))
                .as(String.valueOf(state)).isFalse();
        }
        assertThat((boolean) classify.invoke(null, new java.io.IOException("socket closed"), SQLDialect.POSTGRES))
            .as("an I/O failure with no state").isFalse();
        assertThat((boolean) classify.invoke(null, new SQLException("killed", "72000", 28), SQLDialect.POSTGRES))
            .as("Oracle's vendor codes are read on Oracle only").isFalse();
    }

    @Test
    void theEscapeHatch_registersNothing() throws Throwable {
        var env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(GraphQLContext.newContext().build())
            .executionStepInfo(step())
            .build();
        // No carrier in the context: a warning naming the field, and no failure.
        onFieldRollback(env, (dsl, claimed) -> written.add(new Object[] {dsl, claimed}));
        assertThat(written).isEmpty();
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** A runtime with the given outbox bound, paired with the request source over tenant A and a default pool. */
    private Object newRuntime(Duration outboxBound) throws Throwable {
        var pools = new LinkedHashMap<String, DataSource>();
        pools.put("A", fakeDataSource("A"));
        pools.put(RecordingSource.DEFAULT, fakeDataSource(RecordingSource.DEFAULT));
        Object runtime = runtimeClass.getConstructor(SQLDialect.class, Settings.class, int.class, Duration.class)
            .newInstance(SQLDialect.POSTGRES, new Settings(), 4, Duration.ofMillis(500));
        return new Hosted(runtimeClass.getMethod("withOutboxAcquisitionTimeout", Duration.class).invoke(runtime, outboxBound),
            RecordingSource.create(harness, SCHEMA_PACKAGE, "{}", pools));
    }

    private record Hosted(Object runtime, Object source) {}

    private Object newOwnedConnections(Object runtime) throws Throwable {
        Hosted hosted = (Hosted) runtime;
        Class<?> requestTenantsClass = harness.load(SCHEMA_PACKAGE + "." + ConnectionRuntimeClassGenerator.REQUEST_TENANTS_CLASS_NAME);
        Object requestTenants = requestTenantsClass.getMethod("of", Collection.class).invoke(null, List.of("A"));
        Class<?> sourceClass = harness.load(SCHEMA_PACKAGE + ".ConnectionSource");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object commit = Enum.valueOf((Class) commitPolicyClass, "COMMIT");
        return ownedConnectionsClass.getConstructor(runtimeClass, commitPolicyClass, requestTenantsClass, sourceClass)
            .newInstance(hosted.runtime(), commit, requestTenants, hosted.source());
    }

    private static ExecutionStepInfo step() {
        return ExecutionStepInfo.newExecutionStepInfo()
            .type(Scalars.GraphQLString)
            .path(ResultPath.parse("/m"))
            .build();
    }

    private static DataFetchingEnvironment env(Object carrier) {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(GraphQLContext.newContext().of(ownedConnectionsClass, carrier).build())
            .executionStepInfo(step())
            .mergedField(graphql.execution.MergedField.newMergedField(Field.newField("m").build()).build())
            .build();
    }

    @FunctionalInterface
    private interface Write {
        void write(Object dsl, Object claimed);
    }

    private void onFieldRollback(DataFetchingEnvironment env, Write write) throws Throwable {
        Object proxy = Proxy.newProxyInstance(harness.classLoader(), new Class<?>[] {fieldRollbackClass},
            (p, method, args) -> switch (method.getName()) {
                case "write" -> {
                    write.write(args[0], args[1]);
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == args[0];
                default -> "FieldRollback";
            });
        invoke(() -> ownedConnectionsClass.getMethod("onFieldRollback", DataFetchingEnvironment.class, List.class,
            fieldRollbackClass).invoke(null, env, List.of("Mutation.m.failed"), proxy));
    }

    /** Registers failure messages that record the connection and what the failure was claimed as. */
    private void register(DataFetchingEnvironment env) throws Throwable {
        onFieldRollback(env, (dsl, claimed) -> {
            events.add("written");
            written.add(new Object[] {dsl, claimed});
        });
    }

    private void markFieldRefused(DataFetchingEnvironment env, List<?> errors) throws Throwable {
        invoke(() -> ownedConnectionsClass.getMethod("markFieldRefused", DataFetchingEnvironment.class, List.class)
            .invoke(null, env, errors));
    }

    private void markFieldFailed(DataFetchingEnvironment env, Throwable thrown) throws Throwable {
        invoke(() -> ownedConnectionsClass.getMethod("markFieldFailed", DataFetchingEnvironment.class, Throwable.class)
            .invoke(null, env, thrown));
    }

    /** The generated router's claim over one mapping, of foreign key violations. */
    private Throwable claim(Throwable thrown, DataFetchingEnvironment env) throws Throwable {
        Object mapping = databaseMappingClass.getConstructor(String.class, String.class, String.class, String.class)
            .newInstance("23503", null, null, null);
        Object mappings = java.lang.reflect.Array.newInstance(mappingClass, 1);
        java.lang.reflect.Array.set(mappings, 0, mapping);
        return (Throwable) invoke(() -> errorRouterClass
            .getMethod("claim", Throwable.class, mappings.getClass(), DataFetchingEnvironment.class)
            .invoke(null, thrown, mappings, env));
    }

    private void beginField(Object tc) throws Throwable {
        invoke(() -> ownedConnectionsClass.getMethod("beginField", List.class).invoke(tc, List.of("m")));
    }

    private Throwable settleField(Object tc, boolean failed) throws Throwable {
        return (Throwable) invoke(() -> ownedConnectionsClass.getMethod("settleField", boolean.class).invoke(tc, failed));
    }

    private Object dslFor(Object tc, Object key) throws Throwable {
        return invoke(() -> ownedConnectionsClass.getMethod("dslFor", String.class).invoke(tc, key));
    }

    private List<?> scatter(Object tc, Collection<String> keys, Function<Object, Object> perTenant) throws Throwable {
        return (List<?>) invoke(() -> ownedConnectionsClass
            .getMethod("scatter", Collection.class, Function.class)
            .invoke(tc, keys, perTenant));
    }

    /**
     * Releases a carrier whose field's connection died: its rollback fails at release too, on a
     * connection no longer valid, so release evicts it and carries on without throwing.
     */
    private void releaseDeadConnection(Object tc) throws Throwable {
        events.clear();
        releaseAll(tc);
        assertThat(events).as("the dead connection is evicted at release, never returned")
            .contains("rollback:A", "abort:A")
            .doesNotContain("close:A");
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

    private static Object awaitThen(CountDownLatch latch, Object result) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    private void awaitEvent(String event) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!events.contains(event) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(events).contains(event);
    }

    // --- recording fakes ---------------------------------------------------------------------------

    private DataSource fakeDataSource(String label) {
        return (DataSource) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    int taken = connectionsTaken.merge(label, 1, Integer::sum);
                    var latch = exhaustedAfterFirst.get(label);
                    if (latch != null && taken > 1 && !latch.await(10, TimeUnit.SECONDS)) {
                        throw new SQLException("pool exhausted");
                    }
                    events.add("getConnection:" + label);
                    return fakeConnection(label, taken);
                }
                return objectMethodOrDefault(proxy, method, args, "fakeDataSource:" + label);
            });
    }

    private Connection fakeConnection(String label, int nth) {
        boolean[] autoCommit = {true};
        boolean[] closed = {false};
        return (Connection) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> {
                    if (FakeSessionSettings.isReset((String) args[0])) {
                        yield fakePreparedStatement(false);
                    }
                    boolean connect = !((String) args[0]).contains("'', false");
                    yield fakePreparedStatement(connect && nth > 1 && failSecondMountFor.contains(label));
                }
                case "setAutoCommit" -> {
                    autoCommit[0] = (Boolean) args[0];
                    yield null;
                }
                case "getAutoCommit" -> autoCommit[0];
                case "commit" -> {
                    events.add("commit:" + label);
                    if (failCommitFor.contains(label)) throw new SQLException("deferred constraint violated", "23503");
                    yield null;
                }
                case "createStatement" -> fakeStatement();
                case "rollback" -> {
                    events.add("rollback:" + label);
                    var failure = nth == 1 ? failRollbackFor.get(label) : null;
                    if (failure != null) throw failure;
                    yield null;
                }
                case "getMetaData" -> FakeSessionSettings.postgres(harness.classLoader());
                case "isClosed" -> closed[0];
                case "close" -> {
                    closed[0] = true;
                    events.add("close:" + label);
                    yield null;
                }
                case "abort" -> {
                    closed[0] = true;
                    events.add("abort:" + label);
                    yield null;
                }
                // A connection whose rollback a case made fail is one the server ended: no longer valid.
                case "isValid" -> !(nth == 1 && failRollbackFor.containsKey(label));
                case "toString" -> label + "-conn-" + nth;
                default -> objectMethodOrDefault(proxy, method, args, label);
            });
    }

    private java.sql.Statement fakeStatement() {
        return (java.sql.Statement) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[] {java.sql.Statement.class},
            (proxy, method, args) -> {
                if (method.getName().equals("executeQuery")) {
                    return FakeSessionSettings.resultSet(harness.classLoader(), List.of());
                }
                return objectMethodOrDefault(proxy, method, args, "fakeStatement");
            });
    }

    private PreparedStatement fakePreparedStatement(boolean throwOnExecute) {
        return (PreparedStatement) Proxy.newProxyInstance(
            harness.classLoader(), new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                case "execute" -> {
                    if (throwOnExecute) {
                        throw new SQLException("the mount refused this connection");
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
