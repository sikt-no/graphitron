package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.multitenant.Graphitron;
import no.sikt.graphitron.generated.multitenant.schema.FetcherErrorLog;
import no.sikt.graphitron.generated.multitenant.schema.GraphitronRuntime;
import no.sikt.graphitron.generated.multitenant.schema.SessionMountException;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end execution proof of {@code @tenantFanOut} over the multi-tenant fixture package and
 * real database-per-tenant PostgreSQL: the fanned root field unions rows from every domain tenant
 * in domain order with per-tenant ORDER BY intact; the batched form fans a child of an untenanted
 * parent out per parent batch, tenant-blocked (each tenant's rows sorted by the field's own
 * ordering, blocks concatenated in domain order, never re-sorted globally, because the rows come
 * from different databases and one execution's ORDER BY cannot span them); the projected subtree
 * inherits the tenant within one statement; a
 * claimed-but-unmapped tenant fails the request before any SQL; a downed tenant yields partial
 * data with an appended null element and a path-bearing redacted error under {@code [Film]} and a
 * bubbled null field under {@code [Film!]}; the authorization pre-filter never queries a hosted
 * tenant absent from the request set; and a {@code @splitQuery} child below the fanned field
 * batches per tenant through the loader-name partition, each batch routed to its own source.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class TenantFanOutExecutionTest {

    static DSLContext dsl;                 // default database, out-of-band setup/cleanup
    static String jdbcUrl, jdbcUser, jdbcPassword;

    static final AtomicInteger TENANT_1_OPENED = new AtomicInteger();
    static final AtomicInteger TENANT_2_OPENED = new AtomicInteger();
    static final AtomicBoolean TENANT_2_DOWN = new AtomicBoolean(false);
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        jdbcUrl = SakilaDatabase.url();
        jdbcUser = SakilaDatabase.user();
        jdbcPassword = SakilaDatabase.password();

        dsl = DSL.using(jdbcUrl, jdbcUser, jdbcPassword);

        // Real database-per-tenant with disjoint rows. film_id doubles as the tenant id in the
        // fixture, so tenant 1 holds films 10..11 and tenant 2 film 20 (any values work; the
        // fan-out domain comes from the request, not the rows). language_id points at the
        // fan-out language rows seeded into the default database below (the batched form's
        // untenanted parents).
        for (String db : List.of("fanout_t1", "fanout_t2")) {
            SakilaDatabase.createTenantDatabase(db);
            try (var tenant = DSL.using(SakilaDatabase.tenantUrl(db), jdbcUser, jdbcPassword)) {
                tenant.execute("create table film (film_id int primary key, title text not null,"
                    + " language_id int not null)");
                tenant.execute("create table inventory (inventory_id serial primary key,"
                    + " film_id int not null, store_id int not null)");
                tenant.execute("create table film_actor (actor_id int not null, film_id int not null,"
                    + " primary key (actor_id, film_id))");
                TenantSessionFixture.installSessionObjects(tenant);
            }
        }
        try (var t1 = DSL.using(SakilaDatabase.tenantUrl("fanout_t1"), jdbcUser, jdbcPassword)) {
            // Inserted in reverse film_id order on purpose, and every tenant's films point at
            // ONE shared language (901) whose id ranges interleave across tenants: t1 holds 10
            // and 30, t2 holds 20. That makes the batched child's ordering assertion able to
            // fail three ways. An emission that drops the declared sort returns insertion order
            // (Beta before Alpha); a globally re-sorted merge returns Alpha, Gamma, Beta; only
            // the published contract (sorted within each tenant's block, blocks concatenated in
            // domain order) returns Alpha, Beta, Gamma.
            t1.execute("insert into film values (30, 'T1 Beta', 901), (10, 'T1 Alpha', 901)");
            t1.execute("insert into inventory (film_id, store_id) values (10, 1), (10, 2)");
            t1.execute("insert into film_actor values (100, 10)");
            // Disjoint per-tenant handle-sequence ranges, so a $session read's session number
            // identifies which tenant's mount produced the handle it observed.
            t1.execute("alter sequence session_handle_seq restart with 1000");
        }
        try (var t2 = DSL.using(SakilaDatabase.tenantUrl("fanout_t2"), jdbcUser, jdbcPassword)) {
            // 20 shares language 901 with tenant 1's films, which is what makes the ordering
            // contract observable; 40 sits alone under 902 so a second untenanted parent draws
            // its rows from a database the first parent's rows cannot come from, and a
            // mis-routed batch shows up as a row under the wrong language.
            t2.execute("insert into film values (20, 'T2 Gamma', 901), (40, 'T2 Delta', 902)");
            t2.execute("insert into inventory (film_id, store_id) values (20, 7)");
            t2.execute("insert into film_actor values (200, 20)");
            t2.execute("alter sequence session_handle_seq restart with 2000");
        }
        // The batched form's untenanted parents, in the default database's sakila language table.
        dsl.execute("delete from language where language_id in (901, 902, 903)");
        dsl.execute("insert into language (language_id, name) values"
            + " (901, 'FanOutLangA'), (902, 'FanOutLangB'), (903, 'FanOutLangC')");

        // Ordered: the fan-out domain (and so the union's concatenation order) follows the
        // tenant map's configured key order, which the runtime preserves via LinkedHashMap.
        Map<Integer, DataSource> byTenant = new java.util.LinkedHashMap<>();
        byTenant.put(1, tenantDataSource("fanout_t1", TENANT_1_OPENED, null));
        byTenant.put(2, tenantDataSource("fanout_t2", TENANT_2_OPENED, TENANT_2_DOWN));
        var runtime = new GraphitronRuntime(defaultDataSource(), byTenant, SQLDialect.POSTGRES);
        graphql = runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (dsl != null) {
            dsl.execute("delete from language where language_id in (901, 902, 903)");
            SakilaDatabase.dropTenantDatabase("fanout_t1");
            SakilaDatabase.dropTenantDatabase("fanout_t2");
        }
    }

    @BeforeEach
    void reset() {
        TENANT_2_DOWN.set(false);
        TENANT_1_OPENED.set(0);
        TENANT_2_OPENED.set(0);
    }

    private static ExecutionResult execute(String query, Collection<Integer> tenants) {
        return graphql.execute(Graphitron.newOwnedExecutionInput(tenants, SessionStates.multitenant("{}"))
            .query(query)
            .build());
    }

    @Test
    void fannedRootField_unionsEveryDomainTenantInDomainOrder_withPerTenantOrderIntact() {
        var result = execute("{ filmsEverywhere { title } }", List.of(1, 2));

        assertThat(result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        var films = (List<Map<String, Object>>) data.get("filmsEverywhere");
        assertThat(films).extracting(f -> f.get("title"))
            .as("tenants concatenate in the tenant map's configured order; within a tenant the"
                + " field's ORDER BY (default: primary key) applies; no global re-sort")
            .containsExactly("T1 Alpha", "T1 Beta", "T2 Gamma", "T2 Delta");
    }

    @Test
    void projectedSubtree_inheritsTheTenantWithinOneStatement() {
        // FilmActor projects as a nested multiset inside each tenant's single statement: the
        // actor rows can only come from the same database as their film.
        var result = execute("{ filmsEverywhere { title actors { actorId } } }", List.of(1, 2));

        assertThat(result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        var films = (List<Map<String, Object>>) data.get("filmsEverywhere");
        assertThat(actorIdsOf(films, "T1 Alpha")).containsExactly(100);
        assertThat(actorIdsOf(films, "T2 Gamma")).containsExactly(200);
    }

    @Test
    void splitQueryChildBelowTheFannedField_batchesPerTenantAndRoutesEachBatchToItsSource() {
        // Mixed-tenant parent rows: the inventories DataLoader partitions per tenant through the
        // tenant-segmented loader name, and each partition's batch runs on its own database. The
        // disjoint inventory rows prove the routing; a mixed batch on one source could not
        // return both.
        var result = execute("{ filmsEverywhere { title inventories { inventoryId } } }", List.of(1, 2));

        assertThat(result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        var films = (List<Map<String, Object>>) data.get("filmsEverywhere");
        assertThat(inventoryCountOf(films, "T1 Alpha")).isEqualTo(2);
        assertThat(inventoryCountOf(films, "T1 Beta")).isEqualTo(0);
        assertThat(inventoryCountOf(films, "T2 Gamma")).isEqualTo(1);
    }

    @Test
    void sessionBoundServiceUnderFanOut_readsTwoDistinctHandlesInOneOperation() {
        // The per-key assertion that distinguishes the carrier design from a single
        // request-scoped slot: one operation, two tenants, and the $session-bound service
        // parameter observes each tenant connection's own mounted handle. The tenants'
        // handle sequences occupy disjoint ranges (restarted at 1000 and 2000 in setup), so
        // the session number identifies which tenant's mount produced the observed handle.
        var result = graphql.execute(Graphitron
            .newOwnedExecutionInput(List.of(1, 2), SessionStates.multitenant("{\"sub\":\"fanout-user\"}"))
            .query("{ filmsEverywhere { title mountedPrincipal } }")
            .build());

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        var films = (List<Map<String, Object>>) data.get("filmsEverywhere");
        String t1First = (String) films.get(0).get("mountedPrincipal");
        String t1Second = (String) films.get(1).get("mountedPrincipal");
        String t2 = (String) films.get(2).get("mountedPrincipal");

        assertThat(t1First)
            .as("one pinned connection per tenant: tenant 1's rows share one handle")
            .isEqualTo(t1Second);
        assertThat(t1First).startsWith("fanout-user#");
        assertThat(t2).startsWith("fanout-user#");
        int t1SessionNo = Integer.parseInt(t1First.substring(t1First.indexOf('#') + 1));
        int t2SessionNo = Integer.parseInt(t2.substring(t2.indexOf('#') + 1));
        assertThat(t1SessionNo).as("handle minted by tenant 1's own mount").isBetween(1000, 1999);
        assertThat(t2SessionNo).as("handle minted by tenant 2's own mount").isBetween(2000, 2999);
    }

    @Test
    void sessionMemberBoundServiceUnderFanOut_eachTenantReadsItsOwnSessionNumber() {
        // $session.sessionNo under fan-out: the member read goes through each call's own
        // connection (the statement's dsl), so tenant 1's rows see a number from its 1000s range
        // and tenant 2's from the 2000s, in one operation, and rows of one tenant share the one.
        var result = graphql.execute(Graphitron
            .newOwnedExecutionInput(List.of(1, 2), SessionStates.multitenant("{\"sub\":\"fanout-user\"}"))
            .query("{ filmsEverywhere { title mountedSessionNumber } }")
            .build());

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        var films = (List<Map<String, Object>>) data.get("filmsEverywhere");
        int t1First = (Integer) films.get(0).get("mountedSessionNumber");
        int t1Second = (Integer) films.get(1).get("mountedSessionNumber");
        int t2 = (Integer) films.get(2).get("mountedSessionNumber");

        assertThat(t1First).as("one pinned connection per tenant").isEqualTo(t1Second);
        assertThat(t1First).as("minted by tenant 1's own mount").isBetween(1000, 1999);
        assertThat(t2).as("minted by tenant 2's own mount").isBetween(2000, 2999);
    }

    @Test
    void fannedChildOfUntenantedParent_isTenantBlockedAndSortedWithinEachBlock() {
        // The untenanted parents come from the default source; the fanned child fans each parent
        // batch out per tenant, and each language's films land under the right parent (the
        // per-key merge) from the right database.
        var result = execute("{ languages { name films { title } } }", List.of(1, 2));

        assertThat(result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        var languages = (List<Map<String, Object>>) data.get("languages");
        // The published contract for a fanned list, and the whole of it: rows are sorted by the
        // field's ordering (default: primary key) WITHIN each tenant's block, and the blocks
        // concatenate in domain order. The rows come from different databases, so one execution's
        // ORDER BY cannot span them and the merge deliberately does not re-sort. The seed makes
        // all three readings distinguishable: insertion order is Beta, Alpha, Gamma; a global
        // re-sort by film_id is Alpha, Gamma, Beta; the contract is Alpha, Beta, Gamma.
        assertThat(filmsOf(languages, "FanOutLangA"))
            .as("tenant 1's block sorted, then tenant 2's block sorted; no global re-sort")
            .containsExactly("T1 Alpha", "T1 Beta", "T2 Gamma");
        // The routing half, which the ordering seed above cannot show on its own: a second
        // populated parent whose only rows live in tenant 2's database. A batch that reached
        // the wrong source would put T2 Delta under FanOutLangA, or nothing under FanOutLangB.
        assertThat(filmsOf(languages, "FanOutLangB"))
            .as("a parent whose rows come from one tenant only")
            .containsExactly("T2 Delta");
        // A parent with rows in no tenant still gets its bucket, empty.
        assertThat(filmsOf(languages, "FanOutLangC")).isEmpty();
    }

    @Test
    void claimedButUnmappedTenant_failsTheRequestBeforeAnySql() {
        var result = execute("{ filmsEverywhere { title } }", List.of(1, 99));

        // The correlation id is random hex and can legitimately contain the digits "99", so the
        // tenant key's absence is proven by matching the whole redacted template rather than a
        // negative contains check on the message.
        assertThat(result.getErrors())
            .as("the request's tenant set names an unhosted tenant: a request-level error,"
                + " redacted on the wire (correlation-id reference; the tenant key stays in the"
                + " server log)")
            .anyMatch(e -> e.getMessage().matches("An error occurred\\. Reference: [0-9a-f-]{36}\\."));
        Map<String, Object> data = result.getData();
        assertThat(data == null || data.get("filmsEverywhere") == null).isTrue();
        assertThat(TENANT_1_OPENED.get())
            .as("the error precedes all SQL: no tenant connection was pinned")
            .isZero();
    }

    @Test
    void hostedTenantAbsentFromTheRequestSet_isNeverQueried() {
        var result = execute("{ filmsEverywhere { title } }", List.of(1));

        assertThat(result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        var films = (List<Map<String, Object>>) data.get("filmsEverywhere");
        assertThat(films).extracting(f -> f.get("title")).containsExactly("T1 Alpha", "T1 Beta");
        assertThat(TENANT_2_OPENED.get())
            .as("the authorization pre-filter: a hosted tenant the request did not name is"
                + " silently never queried")
            .isZero();
    }

    @Test
    void downedTenant_yieldsPartialDataWithAnAppendedNullElementAndAPathBearingRedactedError() {
        TENANT_2_DOWN.set(true);
        var result = execute("{ filmsEverywhere { title } }", List.of(1, 2));

        Map<String, Object> data = result.getData();
        var films = (List<Map<String, Object>>) data.get("filmsEverywhere");
        assertThat(films).hasSize(3);
        assertThat(films.get(0)).extracting(f -> f.get("title")).isEqualTo("T1 Alpha");
        assertThat(films.get(1)).extracting(f -> f.get("title")).isEqualTo("T1 Beta");
        assertThat(films.get(2)).as("the downed tenant's single appended null element").isNull();

        assertThat(result.getErrors()).hasSize(1);
        var error = result.getErrors().get(0);
        assertThat(error.getPath())
            .as("the error's path points at the null element's index")
            .containsExactly("filmsEverywhere", 2);
        assertThat(error.getMessage())
            .as("redaction discipline: a correlation-id reference, no cause details")
            .contains("Reference: ")
            .doesNotContain("tenant 2 is down");
        assertThat(error.getExtensions())
            .containsEntry("classification", "TenantFanOutFailed");
    }

    @Test
    void downedTenant_byTimeout_classifiesTenantFanOutTimedOut() {
        // A dedicated engine with a short scatter deadline; tenant 2's acquisition hangs past
        // it, so the join stops waiting and the wire carries the timeout classification.
        Map<Integer, DataSource> byTenant = new java.util.LinkedHashMap<>();
        byTenant.put(1, tenantDataSource("fanout_t1", null, null));
        byTenant.put(2, hangingDataSource("fanout_t2", 5_000));
        var slowRuntime = new GraphitronRuntime(defaultDataSource(), byTenant, SQLDialect.POSTGRES,
            4, java.time.Duration.ofMillis(500));
        var slowEngine = slowRuntime.newGraphQL(Graphitron.buildSchema(b -> {})).build();

        var result = slowEngine.execute(Graphitron.newOwnedExecutionInput(List.of(1, 2), SessionStates.multitenant("{}"))
            .query("{ filmsEverywhere { title } }")
            .build());

        Map<String, Object> data = result.getData();
        var films = (List<Map<String, Object>>) data.get("filmsEverywhere");
        assertThat(films).hasSize(3);
        assertThat(films.get(2)).as("the timed-out tenant's appended null element").isNull();
        assertThat(result.getErrors())
            .anyMatch(e -> e.getExtensions() != null
                && "TenantFanOutTimedOut".equals(e.getExtensions().get("classification")));
    }

    /** One {@link FetcherErrorLog#tenantFailed} call. */
    private record LoggedTenantFailure(Integer tenantKey, Throwable cause, String reference) {}

    /** Records tenant failures; an {@code unmatched} call outside a fan-out fails the test loudly. */
    private static FetcherErrorLog recordingTenantLog(List<LoggedTenantFailure> logged) {
        return new FetcherErrorLog() {
            @Override
            public void unmatched(Throwable thrown, String reference, graphql.schema.DataFetchingEnvironment env) {
                throw new AssertionError("expected only tenant failures, got " + thrown);
            }

            @Override
            public void tenantFailed(Integer tenantKey, Throwable cause, String reference,
                                     graphql.schema.DataFetchingEnvironment env) {
                logged.add(new LoggedTenantFailure(tenantKey, cause, reference));
            }
        };
    }

    private static String referenceOf(graphql.GraphQLError error) {
        String message = error.getMessage();
        return message.substring(message.indexOf("Reference: ") + "Reference: ".length(), message.length() - 1);
    }

    @Test
    void downedTenant_reachesTheRuntimesFetcherErrorLogWithTheTenantAndTheClientsReference() {
        TENANT_2_DOWN.set(true);
        var logged = new java.util.concurrent.CopyOnWriteArrayList<LoggedTenantFailure>();
        Map<Integer, DataSource> byTenant = new java.util.LinkedHashMap<>();
        byTenant.put(1, tenantDataSource("fanout_t1", null, null));
        byTenant.put(2, tenantDataSource("fanout_t2", null, TENANT_2_DOWN));
        var engine = new GraphitronRuntime(defaultDataSource(), byTenant, SQLDialect.POSTGRES)
            .withFetcherErrorLog(recordingTenantLog(logged))
            .newGraphQL(Graphitron.buildSchema(b -> {})).build();

        var result = engine.execute(Graphitron.newOwnedExecutionInput(List.of(1, 2), SessionStates.multitenant("{}"))
            .query("{ filmsEverywhere { title } }")
            .build());

        assertThat(result.getErrors()).hasSize(1);
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).tenantKey()).isEqualTo(2);
        assertThat(logged.get(0).cause()).isNotInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(logged.get(0).reference()).isEqualTo(referenceOf(result.getErrors().get(0)));
    }

    @Test
    void rejectedMountOnRacingScatterWorkers_rejectsTheRequestOnce_overridingPartialData() {
        // Both tenants' workers mount concurrently and both are rejected: one winner is remembered
        // for the operation, the second root field never mounts again, no tenant failure is
        // logged, and execute throws in place of partial data.
        var logged = new java.util.concurrent.CopyOnWriteArrayList<LoggedTenantFailure>();
        Map<Integer, DataSource> byTenant = new java.util.LinkedHashMap<>();
        byTenant.put(1, tenantDataSource("fanout_t1", TENANT_1_OPENED, null));
        byTenant.put(2, tenantDataSource("fanout_t2", TENANT_2_OPENED, null));
        var engine = new GraphitronRuntime(defaultDataSource(), byTenant, SQLDialect.POSTGRES)
            .withFetcherErrorLog(recordingTenantLog(logged))
            .newGraphQL(Graphitron.buildSchema(b -> {})).build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.execute(Graphitron
                .newOwnedExecutionInput(List.of(1, 2), SessionStates.multitenant("{\"sub\":\"reject-me\"}"))
                .query("{ a: filmsEverywhere { title } b: filmsEverywhere { title } }")
                .build()))
            .isInstanceOf(SessionMountException.class)
            .hasStackTraceContaining("unentitled principal");
        assertThat(TENANT_1_OPENED.get() + TENANT_2_OPENED.get())
            .as("each tenant's worker tried at most once; the second field reused the remembered rejection")
            .isBetween(1, 2);
        assertThat(logged).as("a rejected identity is not a tenant outage").isEmpty();
    }

    @Test
    void timedOutTenant_reachesTheRuntimesFetcherErrorLogAsATimeoutException() {
        var logged = new java.util.concurrent.CopyOnWriteArrayList<LoggedTenantFailure>();
        Map<Integer, DataSource> byTenant = new java.util.LinkedHashMap<>();
        byTenant.put(1, tenantDataSource("fanout_t1", null, null));
        byTenant.put(2, hangingDataSource("fanout_t2", 5_000));
        var engine = new GraphitronRuntime(defaultDataSource(), byTenant, SQLDialect.POSTGRES,
                4, java.time.Duration.ofMillis(500))
            .withFetcherErrorLog(recordingTenantLog(logged))
            .newGraphQL(Graphitron.buildSchema(b -> {})).build();

        var result = engine.execute(Graphitron.newOwnedExecutionInput(List.of(1, 2), SessionStates.multitenant("{}"))
            .query("{ filmsEverywhere { title } }")
            .build());

        assertThat(result.getErrors()).hasSize(1);
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).tenantKey()).isEqualTo(2);
        assertThat(logged.get(0).cause()).isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(logged.get(0).reference()).isEqualTo(referenceOf(result.getErrors().get(0)));
    }

    @Test
    void downedTenant_underStrictElements_bubblesTheNullToTheWholeField() {
        TENANT_2_DOWN.set(true);
        var result = execute("{ filmsEverywhereStrict { title } }", List.of(1, 2));

        Map<String, Object> data = result.getData();
        assertThat(data.get("filmsEverywhereStrict"))
            .as("[Film!] lets graphql-java's null-bubbling turn any tenant failure into a null field")
            .isNull();
        assertThat(result.getErrors())
            .anyMatch(e -> e.getExtensions() != null
                && "TenantFanOutFailed".equals(e.getExtensions().get("classification")));
    }

    // ===== helpers =====

    private static List<Integer> actorIdsOf(List<Map<String, Object>> films, String title) {
        var actors = (List<Map<String, Object>>) filmByTitle(films, title).get("actors");
        return actors.stream().map(a -> (Integer) a.get("actorId")).toList();
    }

    private static int inventoryCountOf(List<Map<String, Object>> films, String title) {
        return ((List<?>) filmByTitle(films, title).get("inventories")).size();
    }

    private static Map<String, Object> filmByTitle(List<Map<String, Object>> films, String title) {
        return films.stream()
            .filter(f -> f != null && title.equals(f.get("title")))
            .findFirst().orElseThrow();
    }

    private static List<Object> filmsOf(List<Map<String, Object>> languages, String name) {
        // sakila's language.name is CHAR(20): trim the padding before comparing.
        var language = languages.stream()
            .filter(l -> l.get("name") != null && name.equals(((String) l.get("name")).trim()))
            .findFirst().orElseThrow();
        var films = (List<Map<String, Object>>) language.get("films");
        return films.stream().map(f -> f.get("title")).toList();
    }

    // ===== Optional tenant arguments narrow the fan-out domain =====

    /**
     * Runs {@code body} with one film per tenant whose film_id names the tenant holding it
     * (film 1 in tenant 1, film 2 in tenant 2), the invariant narrowing rests on and the base
     * seed deliberately does not keep. The tenant databases belong to this class and its methods
     * run one at a time, so the rows are visible to nothing else and leave with the test.
     */
    private static void withTenantOwnedFilms(Runnable body) {
        try (var t1 = DSL.using(SakilaDatabase.tenantUrl("fanout_t1"), jdbcUser, jdbcPassword);
             var t2 = DSL.using(SakilaDatabase.tenantUrl("fanout_t2"), jdbcUser, jdbcPassword)) {
            t1.execute("insert into film values (1, 'T1 Own', 903)");
            t2.execute("insert into film values (2, 'T2 Own', 903)");
            try {
                body.run();
            } finally {
                t1.execute("delete from film where film_id = 1");
                t2.execute("delete from film where film_id = 2");
            }
        }
    }

    private static List<Object> titles(ExecutionResult result, String field) {
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        return ((List<Map<String, Object>>) data.get(field)).stream().map(f -> f.get("title")).toList();
    }

    @Test
    void narrowedFanOut_withTheArgumentOmitted_fansOutOverEveryDomainTenant() {
        var result = execute("{ filmsNarrowable { title } }", List.of(1, 2));

        assertThat(titles(result, "filmsNarrowable"))
            .containsExactly("T1 Alpha", "T1 Beta", "T2 Gamma", "T2 Delta");
        assertThat(TENANT_1_OPENED.get()).isPositive();
        assertThat(TENANT_2_OPENED.get()).isPositive();
    }

    @Test
    void narrowedFanOut_withTheArgumentGiven_queriesOnlyTheTenantItNames() {
        withTenantOwnedFilms(() -> {
            var result = execute("{ filmsNarrowable(filmId: 2) { title } }", List.of(1, 2));

            assertThat(titles(result, "filmsNarrowable")).containsExactly("T2 Own");
            assertThat(TENANT_1_OPENED.get())
                .as("tenant 1 holds no row naming tenant 2, so it is never asked")
                .isZero();
            assertThat(TENANT_2_OPENED.get()).isPositive();
        });
    }

    @Test
    void narrowedFanOut_namingATenantOutsideTheRequestSet_answersNoRowsAndOpensNothing() {
        withTenantOwnedFilms(() -> {
            var result = execute("{ filmsNarrowable(filmId: 2) { title } }", List.of(1));

            assertThat(titles(result, "filmsNarrowable")).isEmpty();
            assertThat(TENANT_1_OPENED.get()).isZero();
            assertThat(TENANT_2_OPENED.get()).isZero();
        });
    }

    @Test
    void narrowedFanOut_overAListOfNodeIds_queriesExactlyTheTenantsTheIdsName() {
        var film1 = no.sikt.graphitron.generated.multitenant.util.NodeIdEncoder.encodeFilm(1);
        var film2 = no.sikt.graphitron.generated.multitenant.util.NodeIdEncoder.encodeFilm(2);
        withTenantOwnedFilms(() -> {
            var both = execute("{ filmsNarrowableByIds(ids: [\"" + film1 + "\", \"" + film2
                + "\"]) { title } }", List.of(1, 2));
            assertThat(titles(both, "filmsNarrowableByIds")).containsExactly("T1 Own", "T2 Own");

            reset();
            var one = execute("{ filmsNarrowableByIds(ids: [\"" + film2 + "\"]) { title } }",
                List.of(1, 2));
            assertThat(titles(one, "filmsNarrowableByIds")).containsExactly("T2 Own");
            assertThat(TENANT_1_OPENED.get())
                .as("the decoded String segment coerces to the Integer key, so tenant 1 drops out")
                .isZero();
        });
    }

    @Test
    void narrowedFanOut_withAnExplicitNullOrAnEmptyList_constrainsNothingAndFansTheWholeDomain() {
        // Neither names a tenant, so neither narrows: every domain tenant is asked. (A null
        // element inside the id list would narrow nothing too, but the field's own node-id filter
        // refuses a null id before any statement runs, so this fixture cannot show it.)
        var explicitNull = execute("{ filmsNarrowable(filmId: null) { title } }", List.of(1, 2));
        assertThat(titles(explicitNull, "filmsNarrowable"))
            .containsExactly("T1 Alpha", "T1 Beta", "T2 Gamma", "T2 Delta");
        assertThat(TENANT_1_OPENED.get()).isPositive();
        assertThat(TENANT_2_OPENED.get()).isPositive();

        reset();
        var emptyList = execute("{ filmsNarrowableByIds(ids: []) { title } }", List.of(1, 2));
        assertThat(emptyList.getErrors()).as("errors: " + emptyList.getErrors()).isEmpty();
        assertThat(TENANT_1_OPENED.get()).isPositive();
        assertThat(TENANT_2_OPENED.get()).isPositive();
    }

    private static DataSource defaultDataSource() {
        return dataSource(jdbcUrl, null, null);
    }

    private static DataSource tenantDataSource(String db, AtomicInteger opened, AtomicBoolean down) {
        return dataSource(SakilaDatabase.tenantUrl(db), opened, down);
    }

    /** A DataSource whose acquisition hangs past the scatter deadline, then connects normally. */
    private static DataSource hangingDataSource(String db, long hangMillis) {
        String url = SakilaDatabase.tenantUrl(db);
        return (DataSource) Proxy.newProxyInstance(
            TenantFanOutExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    Thread.sleep(hangMillis);
                    return SakilaDatabase.connect(url, jdbcUser, jdbcPassword);
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "hangingDataSource:" + db;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    }

    private static DataSource dataSource(String url, AtomicInteger opened, AtomicBoolean down) {
        return (DataSource) Proxy.newProxyInstance(
            TenantFanOutExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    if (down != null && down.get()) {
                        throw new SQLException("tenant 2 is down");
                    }
                    if (opened != null) opened.incrementAndGet();
                    return SakilaDatabase.connect(url, jdbcUser, jdbcPassword);
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "dataSource:" + url;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    }
}
