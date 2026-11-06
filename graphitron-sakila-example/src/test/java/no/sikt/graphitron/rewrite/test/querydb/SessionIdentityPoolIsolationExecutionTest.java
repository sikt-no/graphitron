package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.execution.AbortExecutionException;
import graphql.execution.instrumentation.FieldFetchingInstrumentationContext;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters;
import graphql.schema.DataFetcher;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.FetcherErrorLog;
import no.sikt.graphitron.generated.schema.SessionMountException;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import no.sikt.graphitron.sakila.example.app.SakilaSessions;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Execution-tier proof that session identity never crosses requests on the owned-connection
 * path, end to end through a real connection pool. Every request here runs through
 * {@code GraphitronRuntime.newGraphQL(schema)} over an Agroal pool of size one, so the request
 * after it is certain to get the same physical connection: the database session that carries
 * {@code app.user_id}, which a return to the pool does not reset. The pool connects as a
 * non-superuser role of this class's own (superusers bypass row-level security, and roles are
 * cluster-global while classes in this module run concurrently).
 *
 * <p>What a caller may read is decided by {@code secure_note}'s row-level security policy alone:
 * {@code Query.secureNotes} carries no condition, so a request sees whichever identity the
 * connection it ran on carried. Identity is mounted and cleared by the example's own callbacks
 * ({@code SakilaSessions}). After each request, {@link #identityOnNextBorrow} takes the pool's
 * one connection outside the engine and reads {@code app.user_id}, which must be empty; the
 * pool's acquire and destroy counters say whether the request borrowed at all and whether the
 * connection was returned or evicted, since an evicted connection, or one never borrowed, would
 * read empty trivially.
 *
 * <p>The suite covers the happy path (one user, then another user, an anonymous request, or a
 * borrower outside Graphitron on the same connection), mutation fields that fail in the service,
 * at commit, or in a nested transaction the service caught, a query field that fails without
 * touching the database, a rejected mount, a cancel before execution, a cancel noticed while a
 * field completes, a cancel and a thrown exception during the synchronous dispatch of a later
 * root field, a consumer fetcher that asks for a connection after a cancel released the
 * operation's, the multi-tenant carrier's own release loop, and a consumer unmount that clears
 * nothing, which the PostgreSQL reset at release backs up. Each failure path also
 * checks that the failure the log recorded is the one the case names, not an earlier permission
 * error.
 *
 * <p>Not yet covered, because it is a known defect today and a case here would either fail or
 * assert the defect: what the pool does after a rejected mount. Graphitron evicts with
 * {@code abort()}, which closes the physical connection, but Agroal takes the aborted wrapper back
 * like any return and hands the dead connection to the next borrower, whose request then fails.
 * Identity cannot cross there, so the rejected-mount case pins that the database session is gone
 * and runs on a pool of its own.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class SessionIdentityPoolIsolationExecutionTest {

    static final String ROLE = "pool_isolation_probe";
    static final String ROLE_PASSWORD = "probe";
    static final String ALICE = "pool-alice";
    static final String BOB = "pool-bob";
    static final List<String> ALICE_NOTES = List.of("pool-alice-1", "pool-alice-2");
    static final List<String> BOB_NOTES = List.of("pool-bob-1");
    static final String MARKER_PREFIX = "pool-isolation-";
    static final String TENANT_1 = "poolisolation_t1";
    static final String TENANT_2 = "poolisolation_t2";

    static DSLContext dsl;                 // superuser, out-of-band setup and cleanup (bypasses RLS)
    static AgroalDataSource pool;          // the single-tenant engine's source, a pool of one
    static AgroalDataSource defaultPool;   // the multi-tenant engine's default source
    static AgroalDataSource tenant1Pool;
    static AgroalDataSource tenant2Pool;
    static GraphQL engine;
    static GraphQL cancellingEngine;
    static GraphQL cancelAtDispatchEngine;
    static GraphQL throwAtDispatchEngine;
    static GraphQL tenantEngine;

    /** Every throwable the runtime's fetcher-error log was handed, in order. */
    static final List<Throwable> LOGGED = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setUp() throws SQLException {
        dsl = DSL.using(SakilaDatabase.url(), SakilaDatabase.user(), SakilaDatabase.password());

        dropRole();
        dsl.execute("create role " + ROLE + " login password '" + ROLE_PASSWORD + "'");
        dsl.execute("grant select on secure_note to " + ROLE);
        dsl.execute("grant insert, select on mutation_scope_marker, mutation_scope_deferred_child to " + ROLE);
        dsl.execute("grant usage on sequence mutation_scope_marker_id_seq, mutation_scope_deferred_child_id_seq,"
            + " session_handle_seq to " + ROLE);

        deleteSeededNotes();
        dsl.execute("insert into secure_note (owner_id, note) values"
            + " ('" + ALICE + "', 'pool-alice-1'), ('" + ALICE + "', 'pool-alice-2'), ('" + BOB + "', 'pool-bob-1')");

        pool = poolOfOne(SakilaDatabase.url(), ROLE, ROLE_PASSWORD);
        FetcherErrorLog log = new FetcherErrorLog() {
            @Override public void unmatched(Throwable thrown, String reference, graphql.schema.DataFetchingEnvironment env) {
                LOGGED.add(thrown);
            }
            @Override public void matched(Throwable thrown, Throwable claimed, String reference,
                                          graphql.schema.DataFetchingEnvironment env) {
                LOGGED.add(thrown);
            }
        };
        var runtime = Graphitron.runtime(pool, SQLDialect.POSTGRES).withFetcherErrorLog(log);
        var schema = Graphitron.buildSchema(b -> {});
        engine = runtime.newGraphQL(schema).build();
        cancellingEngine = runtime.newGraphQL(schema, new CancelAtFieldFetching("b")).build();
        cancelAtDispatchEngine = runtime.newGraphQL(schema, new AbortAtFieldExecution("b", false)).build();
        throwAtDispatchEngine = runtime.newGraphQL(schema, new AbortAtFieldExecution("b", true)).build();

        setUpTenants();
    }

    private static void setUpTenants() throws SQLException {
        int base = 1000;
        for (String stem : List.of(TENANT_1, TENANT_2)) {
            SakilaDatabase.createTenantDatabase(stem);
            try (var tenant = DSL.using(SakilaDatabase.tenantUrl(stem), SakilaDatabase.user(), SakilaDatabase.password())) {
                tenant.execute("create table film (film_id int primary key, title text not null,"
                    + " language_id int not null)");
                tenant.execute("insert into film values (1, 'Pool " + stem + "', 1)");
                TenantSessionFixture.installSessionObjects(tenant);
                // Disjoint handle ranges, so a handle's session number names the tenant that minted it.
                tenant.execute("alter sequence session_handle_seq restart with " + base);
            }
            base += 1000;
        }
        defaultPool = poolOfOne(SakilaDatabase.url(), SakilaDatabase.user(), SakilaDatabase.password());
        tenant1Pool = poolOfOne(SakilaDatabase.tenantUrl(TENANT_1), SakilaDatabase.user(), SakilaDatabase.password());
        tenant2Pool = poolOfOne(SakilaDatabase.tenantUrl(TENANT_2), SakilaDatabase.user(), SakilaDatabase.password());
        Map<Integer, DataSource> byTenant = new LinkedHashMap<>();
        byTenant.put(1, tenant1Pool);
        byTenant.put(2, tenant2Pool);
        var runtime = new no.sikt.graphitron.generated.multitenant.schema.GraphitronRuntime(
            defaultPool, byTenant, SQLDialect.POSTGRES);
        tenantEngine = runtime.newGraphQL(
            no.sikt.graphitron.generated.multitenant.Graphitron.buildSchema(b -> {})).build();
    }

    @AfterAll
    static void tearDown() {
        for (var source : new AgroalDataSource[]{pool, defaultPool, tenant1Pool, tenant2Pool}) {
            if (source != null) {
                source.close();
            }
        }
        if (dsl != null) {
            deleteSeededNotes();
            dsl.execute("delete from mutation_scope_deferred_child where marker like '" + MARKER_PREFIX + "%'");
            dsl.execute("delete from mutation_scope_marker where marker like '" + MARKER_PREFIX + "%'");
            dropRole();
            SakilaDatabase.dropTenantDatabase(TENANT_1);
            SakilaDatabase.dropTenantDatabase(TENANT_2);
        }
    }

    @BeforeEach
    void clearLog() {
        LOGGED.clear();
    }

    // ===== the happy path =====

    @Test
    void oneUser_seesExactlyTheirRows_andTheConnectionGoesBackCleared() throws SQLException {
        var before = Counts.of(pool);

        assertThat(secureNotesAs(ALICE)).containsExactlyElementsOf(ALICE_NOTES);

        assertThat(Counts.of(pool).since(before)).as("borrowed once, returned, not evicted")
            .isEqualTo(new Counts(1, 0));
        assertThat(identityOnNextBorrow(pool)).as("app.user_id on the next borrow").isNullOrEmpty();
    }

    @Test
    void secondUserOnTheSamePhysicalConnection_seesOnlyTheirOwnRows() throws SQLException {
        int backend = backendOnNextBorrow(pool);
        var before = Counts.of(pool);

        assertThat(secureNotesAs(ALICE)).containsExactlyElementsOf(ALICE_NOTES);
        assertThat(secureNotesAs(BOB)).as("Bob, after Alice, on her connection").containsExactlyElementsOf(BOB_NOTES);

        assertThat(Counts.of(pool).since(before)).isEqualTo(new Counts(2, 0));
        assertThat(backendOnNextBorrow(pool))
            .as("no connection was destroyed, so both requests ran on one database session")
            .isEqualTo(backend);
    }

    @Test
    void unmountThatClearsNothing_isBackstoppedByTheResetAtRelease() throws SQLException {
        // set_config(..., true) in autocommit reverts at the end of its own statement: this unmount
        // clears nothing, and only the reset graphitron runs after it keeps Alice off the connection.
        String claims = "{\"sub\":\"" + ALICE + "\"}";
        var defective = no.sikt.graphitron.generated.schema.SessionState.of(
            session -> no.sikt.graphitron.rewrite.test.services.SakilaSessionIdentity.mount(session.configuration(), claims),
            (session, handle) -> session.configuration().dsl().execute("select set_config('app.user_id', '', true)"));
        var before = Counts.of(pool);

        var result = engine.execute(Graphitron.newOwnedExecutionInput(defective, claims, ALICE)
            .query("{ secureNotes { note } }")
            .build());
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat(notes(result, "secureNotes")).containsExactlyElementsOf(ALICE_NOTES);

        assertLeftNoIdentity(before, new Counts(1, 0));
    }

    @Test
    void anonymousRequestAfterAUser_seesNoRows() {
        var before = Counts.of(pool);

        assertThat(secureNotesAs(ALICE)).containsExactlyElementsOf(ALICE_NOTES);
        assertThat(secureNotesAs(null)).as("an anonymous request after Alice's").isEmpty();

        assertThat(Counts.of(pool).since(before)).isEqualTo(new Counts(2, 0));
    }

    @Test
    void borrowerOutsideGraphitronAfterAUser_readsNoRows() throws SQLException {
        var before = Counts.of(pool);
        assertThat(secureNotesAs(ALICE)).containsExactlyElementsOf(ALICE_NOTES);
        assertThat(Counts.of(pool).since(before)).isEqualTo(new Counts(1, 0));

        // Anything else sharing the pool: it takes the connection and never mounts anything.
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select note from secure_note")) {
            assertThat(rows.next()).as("a plain borrower after Alice reads none of her rows").isFalse();
        }
        assertThat(Counts.of(pool).since(before).destroyed()).isZero();
    }

    // ===== failure paths, each run as Alice =====

    @Test
    void mutationFieldWhoseServiceThrowsAfterWriting_returnsTheConnectionCleared() throws SQLException {
        var before = Counts.of(pool);

        var result = executeAs(ALICE, "mutation { bad: scopeWriteTwiceThenThrow(marker: \"%s\") }".formatted(marker()));

        assertThat(errorPaths(result)).containsExactly(List.of("bad"));
        assertLoggedOnly(t -> t instanceof IllegalStateException
            && "mutation-scope fixture: failing after two writes".equals(t.getMessage()));
        assertLeftNoIdentity(before, new Counts(1, 0));
    }

    @Test
    void mutationFieldWhoseCommitFailsAtSettle_returnsTheConnectionCleared() throws SQLException {
        var before = Counts.of(pool);

        var result = executeAs(ALICE, "mutation { orphan: scopeWriteADeferredOrphan(marker: \"%s\") }".formatted(marker()));

        assertThat(errorPaths(result)).containsExactly(List.of("orphan"));
        assertLoggedOnly(t -> chainHasSqlState(t, "23503"));
        assertLeftNoIdentity(before, new Counts(1, 0));
    }

    @Test
    void mutationFieldWhoseCaughtNestedTransactionFailed_returnsTheConnectionCleared() throws SQLException {
        var before = Counts.of(pool);

        var result = executeAs(ALICE, "mutation { n: scopeNestedFailsCaught(marker: \"%s\") }".formatted(marker()));

        assertThat(errorPaths(result)).containsExactly(List.of("n"));
        assertLoggedOnly(t -> t.getClass().getSimpleName().equals("NestedScopeFailedException")
            && chainHasMessage(t, "mutation-scope fixture: the nested scope fails"));
        assertLeftNoIdentity(before, new Counts(1, 0));
    }

    @Test
    void queryFieldFailingWithoutTheDatabase_besideOneThatReads_mountsOnceAndReleasesCleanly() throws SQLException {
        var before = Counts.of(pool);

        var result = executeAs(ALICE,
            "{ boom: scopeQueryFailsWithoutDatabase(marker: \"x\") notes: secureNotes { note } }");

        assertThat(errorPaths(result)).containsExactly(List.of("boom"));
        assertThat(notes(result, "notes")).containsExactlyElementsOf(ALICE_NOTES);
        assertLoggedOnly(t -> t instanceof IllegalStateException
            && "mutation-scope fixture: failing without a database".equals(t.getMessage()));
        assertLeftNoIdentity(before, new Counts(1, 0));
    }

    @Test
    void rejectedMount_closesThePhysicalConnectionItBorrowed() throws SQLException {
        // A pool of its own: what the pool does with the aborted connection afterwards is not
        // what this case pins (see the class javadoc), and it must not reach the other cases.
        try (AgroalDataSource own = poolOfOne(SakilaDatabase.url(), ROLE, ROLE_PASSWORD)) {
            var ownEngine = Graphitron.runtime(own, SQLDialect.POSTGRES)
                .newGraphQL(Graphitron.buildSchema(b -> {})).build();
            int backend = backendOnNextBorrow(own);
            var before = Counts.of(own);

            assertThatThrownBy(() -> ownEngine.execute(ownedInput("reject-me", "{ secureNotes { note } }")))
                .isInstanceOf(SessionMountException.class)
                .hasStackTraceContaining("unentitled principal")
                .satisfies(thrown -> assertThat(chainHasSqlState(thrown, "42501"))
                    .as("the mount was refused by the routine, not by a missing grant").isFalse());

            assertThat(Counts.of(own).since(before).acquired()).as("the request borrowed once").isEqualTo(1);
            assertThat(backendGone(backend))
                .as("the database session the rejected mount ran on is gone, so nothing it held can reach"
                    + " another borrower")
                .isTrue();
        }
    }

    @Test
    void cancelBeforeExecute_neverStartsTheOperation_soNothingIsMounted() throws SQLException {
        var before = Counts.of(pool);
        ExecutionInput input = ownedInput(ALICE, "{ secureNotes { note } }");
        input.cancel();

        ExecutionResult result = engine.execute(input);

        assertAborted(result);
        assertThat(Counts.of(pool).since(before)).as("a cancelled request never takes a connection")
            .isEqualTo(new Counts(0, 0));
        assertThat(identityOnNextBorrow(pool)).isNullOrEmpty();
    }

    @Test
    void cancelNoticedWhileAFieldCompletes_releasesThePinnedConnectionCleared() throws SQLException {
        // The test instrumentation cancels at beginFieldFetching of b: after graphql-java's own
        // cancel check in fetchField, so b's fetcher runs on the connection a pinned and mounted,
        // and the cancel is noticed in completeField, where it fails the field's future and the
        // operation completes. Single-tenant fetchers run on the calling thread, so a has fetched
        // before b begins.
        var before = Counts.of(pool);

        ExecutionResult result = cancellingEngine.execute(
            ownedInput(ALICE, "{ a: secureNotes { note } b: secureNotes { note } }"));

        assertAborted(result);
        assertThat(Counts.of(pool).since(before))
            .as("one connection, pinned and mounted by a, reused by b, returned not evicted")
            .isEqualTo(new Counts(1, 0));
        assertThat(identityOnNextBorrow(pool)).isNullOrEmpty();
        assertThat(secureNotesAs(null)).isEmpty();
    }

    @Test
    void cancelDuringTheSynchronousDispatchOfALaterRootField_releasesThePinnedConnectionCleared()
            throws SQLException {
        // Cancelled at beginFieldExecution of b: graphql-java notices it in its per-field check,
        // outside the try that turns an abort into a failed field, so it escapes the strategy
        // synchronously. a has already pinned and mounted the pool's one connection.
        var before = Counts.of(pool);

        ExecutionResult result = cancelAtDispatchEngine.execute(
            ownedInput(ALICE, "{ a: secureNotes { note } b: secureNotes { note } }"));

        assertAborted(result);
        assertThat(Counts.of(pool).since(before)).isEqualTo(new Counts(1, 0));
        assertThat(identityOnNextBorrow(pool))
            .as("the next borrow returns at all, rather than waiting on a connection never released,"
                + " and reads no identity")
            .isNullOrEmpty();
        assertThat(secureNotesAs(null)).isEmpty();
    }

    @Test
    void exceptionDuringTheSynchronousDispatchOfALaterRootField_releasesThePinnedConnectionCleared()
            throws SQLException {
        var before = Counts.of(pool);

        var outcome = throwAtDispatchEngine.executeAsync(
            ownedInput(ALICE, "{ a: secureNotes { note } b: secureNotes { note } }"));

        assertThat(outcome).failsWithin(Duration.ofSeconds(30))
            .withThrowableOfType(java.util.concurrent.ExecutionException.class)
            .withCauseInstanceOf(InjectedFailure.class);
        assertThat(Counts.of(pool).since(before)).isEqualTo(new Counts(1, 0));
        assertThat(identityOnNextBorrow(pool))
            .as("the next borrow returns at all, rather than waiting on a connection never released,"
                + " and reads no identity")
            .isNullOrEmpty();
        assertThat(secureNotesAs(null)).isEmpty();
    }

    @Test
    void fetcherThatAsksForAConnectionAfterACancelReleased_isRefused_andTheNextBorrowIsClean()
            throws Exception {
        // late stands in for a consumer fetcher whose work outlives its operation: it returns a
        // future at once and asks for the default connection only when the test lets it, after b's
        // dispatch cancelled the request and release returned the pool's one connection.
        var proceed = new CountDownLatch(1);
        var refusal = new AtomicReference<Throwable>();
        var straggler = new CompletableFuture<Void>();
        var stragglerEngine = Graphitron.runtime(pool, SQLDialect.POSTGRES)
            .newGraphQL(Graphitron.buildSchema(b -> {}), new StragglerThenCancel("late", "b", proceed, refusal, straggler))
            .build();
        var before = Counts.of(pool);

        ExecutionResult result = stragglerEngine.execute(
            ownedInput(ALICE, "{ a: secureNotes { note } late: secureNotes { note } b: secureNotes { note } }"));
        assertAborted(result);
        assertThat(Counts.of(pool).since(before)).as("a pinned the one connection; release returned it")
            .isEqualTo(new Counts(1, 0));

        proceed.countDown();
        straggler.get(30, TimeUnit.SECONDS);
        assertThat(refusal.get())
            .as("the late request is refused rather than handed a fresh connection nothing will return")
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("A connection was requested after the operation had released its connections.");
        assertThat(Counts.of(pool).since(before)).as("the refused request borrowed nothing")
            .isEqualTo(new Counts(1, 0));
        assertThat(identityOnNextBorrow(pool))
            .as("the next borrow returns at all, rather than waiting on a connection never released,"
                + " and reads no identity")
            .isNullOrEmpty();
        assertThat(secureNotesAs(null)).isEmpty();
    }

    // ===== the multi-tenant carrier =====

    @Test
    void multiTenantFanOut_returnsEveryTenantConnectionCleared() throws SQLException {
        var defaultBefore = Counts.of(defaultPool);
        var t1Before = Counts.of(tenant1Pool);
        var t2Before = Counts.of(tenant2Pool);

        var result = tenantEngine.execute(no.sikt.graphitron.generated.multitenant.Graphitron
            .newOwnedExecutionInput(List.of(1, 2), SessionStates.multitenant("{\"sub\":\"" + ALICE + "\"}"))
            .query("{ filmsEverywhere { title mountedPrincipal } }")
            .build());

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        var films = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("filmsEverywhere");
        assertThat(films).extracting(f -> (String) f.get("mountedPrincipal"))
            .as("identity was mounted on each tenant's connection, by that tenant's own mount")
            .satisfiesExactly(
                t1 -> assertThat(t1).matches(ALICE + "#1\\d{3}"),
                t2 -> assertThat(t2).matches(ALICE + "#2\\d{3}"));

        assertThat(Counts.of(defaultPool).since(defaultBefore)).as("the default source was not touched")
            .isEqualTo(new Counts(0, 0));
        assertThat(Counts.of(tenant1Pool).since(t1Before)).isEqualTo(new Counts(1, 0));
        assertThat(Counts.of(tenant2Pool).since(t2Before)).isEqualTo(new Counts(1, 0));
        assertThat(identityOnNextBorrow(tenant1Pool)).isNullOrEmpty();
        assertThat(identityOnNextBorrow(tenant2Pool)).isNullOrEmpty();
    }

    // ===== helpers =====

    /** The pool's acquire and destroy counts, or the difference between two readings. */
    record Counts(long acquired, long destroyed) {
        static Counts of(AgroalDataSource source) {
            var metrics = source.getMetrics();
            return new Counts(metrics.acquireCount(), metrics.destroyCount());
        }

        Counts since(Counts earlier) {
            return new Counts(acquired - earlier.acquired, destroyed - earlier.destroyed);
        }
    }

    /**
     * The three checks every failure path ends on: the request borrowed and returned (or evicted)
     * as {@code expected} says, the next borrow reads no identity, and an anonymous request after
     * it reads no rows.
     */
    private static void assertLeftNoIdentity(Counts before, Counts expected) throws SQLException {
        assertThat(Counts.of(pool).since(before)).isEqualTo(expected);
        assertThat(identityOnNextBorrow(pool)).as("app.user_id on the next borrow").isNullOrEmpty();
        assertThat(secureNotesAs(null)).as("an anonymous request after the failure").isEmpty();
    }

    /**
     * That the log recorded the failure the case names, and nothing carrying an insufficient
     * privilege state: a missing grant would fail the field earlier, and the identity checks
     * after it would still pass.
     */
    private static void assertLoggedOnly(Predicate<Throwable> named) {
        assertThat(LOGGED).as("what the fetcher-error log recorded").isNotEmpty()
            .anyMatch(named)
            .noneMatch(t -> chainHasSqlState(t, "42501"));
    }

    private static void assertAborted(ExecutionResult result) {
        Object data = result.getData();
        assertThat(data).isNull();
        assertThat(result.getErrors()).as("the abort is what answered the request")
            .anyMatch(e -> e instanceof AbortExecutionException);
    }

    /** Takes a connection from {@code source} outside the engine and reads {@code app.user_id}. */
    private static String identityOnNextBorrow(AgroalDataSource source) throws SQLException {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select current_setting('app.user_id', true)")) {
            rows.next();
            return rows.getString(1);
        }
    }

    /** Whether the backend {@code pid} has exited, waiting briefly for the server to notice a closed socket. */
    private static boolean backendGone(int pid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (dsl.fetchCount(DSL.table("pg_stat_activity"), DSL.field("pid").eq(pid)) == 0) {
                return true;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        return false;
    }

    /** The database backend the pool's one connection is, read outside the engine. */
    private static int backendOnNextBorrow(AgroalDataSource source) throws SQLException {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static List<String> secureNotesAs(String sub) {
        var result = executeAs(sub, "{ secureNotes { note } }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        return notes(result, "secureNotes");
    }

    private static List<String> notes(ExecutionResult result, String field) {
        var rows = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get(field);
        return rows.stream().map(r -> (String) r.get("note")).toList();
    }

    private static ExecutionResult executeAs(String sub, String query) {
        return engine.execute(ownedInput(sub, query));
    }

    /** An owned input whose claims carry {@code sub}; {@code null} is an anonymous request. */
    private static ExecutionInput ownedInput(String sub, String query) {
        String claims = sub == null ? "{}" : "{\"sub\":\"" + sub + "\"}";
        return Graphitron.newOwnedExecutionInput(SakilaSessions.of(claims), claims, sub == null ? "" : sub)
            .query(query)
            .build();
    }

    private static List<List<Object>> errorPaths(ExecutionResult result) {
        return result.getErrors().stream().map(GraphQLError::getPath).toList();
    }

    private static boolean chainHasSqlState(Throwable thrown, String state) {
        for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return true;
            }
            if (t instanceof org.jooq.exception.DataAccessException dae && state.equals(dae.sqlState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean chainHasMessage(Throwable thrown, String message) {
        for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (message.equals(t.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static String marker() {
        return MARKER_PREFIX + UUID.randomUUID();
    }

    private static AgroalDataSource poolOfOne(String url, String user, String password) throws SQLException {
        return AgroalDataSource.from(new AgroalDataSourceConfigurationSupplier()
            .metricsEnabled(true)
            .connectionPoolConfiguration(cp -> cp
                .maxSize(1)
                // A connection that is never returned fails the next borrow instead of hanging the build.
                .acquisitionTimeout(Duration.ofSeconds(10))
                .connectionFactoryConfiguration(cf -> cf
                    .jdbcUrl(url)
                    .principal(new NamePrincipal(user))
                    .credential(new SimplePassword(password)))));
    }

    private static void deleteSeededNotes() {
        dsl.execute("delete from secure_note where owner_id in ('" + ALICE + "', '" + BOB + "')");
    }

    /** Drops the role, first shedding the grants that block a bare {@code drop role}. */
    private static void dropRole() {
        dsl.execute("do $$ begin if exists (select from pg_roles where rolname = '" + ROLE + "')"
            + " then execute 'drop owned by " + ROLE + "'; end if; end $$");
        dsl.execute("drop role if exists " + ROLE);
    }

    /** What {@link AbortAtFieldExecution} throws, so a case can recognise it as the cause. */
    private static final class InjectedFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        InjectedFailure(String alias) {
            super("injected at beginFieldExecution of " + alias);
        }
    }

    /**
     * Cancels the request, or throws, when the root field aliased {@code alias} begins executing:
     * a point the execution strategy reaches during its synchronous dispatch, outside the per-field
     * {@code try} that would turn either into a failed field.
     */
    private static final class AbortAtFieldExecution extends SimplePerformantInstrumentation {
        private final String alias;
        private final boolean throwInstead;

        AbortAtFieldExecution(String alias, boolean throwInstead) {
            this.alias = alias;
            this.throwInstead = throwInstead;
        }

        @Override
        public InstrumentationContext<Object> beginFieldExecution(
                InstrumentationFieldParameters parameters, InstrumentationState state) {
            var path = parameters.getExecutionStepInfo().getPath();
            if (path.getLevel() == 1 && alias.equals(path.getSegmentName())) {
                if (throwInstead) {
                    throw new InjectedFailure(alias);
                }
                parameters.getExecutionContext().getExecutionInput().cancel();
            }
            return super.beginFieldExecution(parameters, state);
        }
    }

    /**
     * Replaces the fetcher of the root field aliased {@code late} with one that returns a pending
     * future and, on another thread, waits for {@code proceed} before asking the field's carrier
     * for the default connection, recording what that call threw into {@code refusal}; then cancels
     * the request when the root field aliased {@code cancelAt} begins executing, as
     * {@link AbortAtFieldExecution} does. {@code done} completes once the late call has returned
     * or thrown.
     */
    private static final class StragglerThenCancel extends SimplePerformantInstrumentation {
        private final String late;
        private final String cancelAt;
        private final CountDownLatch proceed;
        private final AtomicReference<Throwable> refusal;
        private final CompletableFuture<Void> done;

        StragglerThenCancel(String late, String cancelAt, CountDownLatch proceed,
                            AtomicReference<Throwable> refusal, CompletableFuture<Void> done) {
            this.late = late;
            this.cancelAt = cancelAt;
            this.proceed = proceed;
            this.refusal = refusal;
            this.done = done;
        }

        @Override
        public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher,
                InstrumentationFieldFetchParameters parameters, InstrumentationState state) {
            var path = parameters.getExecutionStepInfo().getPath();
            if (path.getLevel() != 1 || !late.equals(path.getSegmentName())) {
                return dataFetcher;
            }
            return env -> CompletableFuture.supplyAsync(() -> {
                try {
                    if (!proceed.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never let the late fetcher proceed");
                    }
                    no.sikt.graphitron.generated.schema.TenantConnections.dslDefault(env);
                } catch (Throwable thrown) {
                    refusal.set(thrown);
                } finally {
                    done.complete(null);
                }
                return List.of();
            });
        }

        @Override
        public InstrumentationContext<Object> beginFieldExecution(
                InstrumentationFieldParameters parameters, InstrumentationState state) {
            var path = parameters.getExecutionStepInfo().getPath();
            if (path.getLevel() == 1 && cancelAt.equals(path.getSegmentName())) {
                parameters.getExecutionContext().getExecutionInput().cancel();
            }
            return super.beginFieldExecution(parameters, state);
        }
    }

    /**
     * Cancels the request when the root field aliased {@code alias} begins fetching. It runs after
     * graphitron's own instrumentation in the chain, and the point it picks lies after
     * graphql-java's cancel check at the top of {@code fetchField}.
     */
    private static final class CancelAtFieldFetching extends SimplePerformantInstrumentation {
        private final String alias;

        CancelAtFieldFetching(String alias) {
            this.alias = alias;
        }

        @Override
        public FieldFetchingInstrumentationContext beginFieldFetching(
                InstrumentationFieldFetchParameters parameters, InstrumentationState state) {
            var path = parameters.getExecutionStepInfo().getPath();
            if (path.getLevel() == 1 && alias.equals(path.getSegmentName())) {
                parameters.getExecutionContext().getExecutionInput().cancel();
            }
            return super.beginFieldFetching(parameters, state);
        }
    }
}
