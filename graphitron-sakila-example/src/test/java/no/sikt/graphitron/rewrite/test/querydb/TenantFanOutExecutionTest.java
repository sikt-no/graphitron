package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.multitenant.Graphitron;
import no.sikt.graphitron.generated.multitenant.schema.GraphitronRuntime;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
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
 * parent out per parent batch; the projected subtree inherits the tenant within one statement; a
 * claimed-but-unmapped tenant fails the request before any SQL; a downed tenant yields partial
 * data with an appended null element and a path-bearing redacted error under {@code [Film]} and a
 * bubbled null field under {@code [Film!]}; the authorization pre-filter never queries a hosted
 * tenant absent from the request set; and a {@code @splitQuery} child below the fanned field
 * batches per tenant through the loader-name partition, each batch routed to its own source.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class TenantFanOutExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;                 // default database, out-of-band setup/cleanup
    static String jdbcUrl, jdbcUser, jdbcPassword;

    static final AtomicInteger TENANT_1_OPENED = new AtomicInteger();
    static final AtomicInteger TENANT_2_OPENED = new AtomicInteger();
    static final AtomicBoolean TENANT_2_DOWN = new AtomicBoolean(false);
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            jdbcUrl = localUrl;
            jdbcUser = System.getProperty("test.db.username", "postgres");
            jdbcPassword = System.getProperty("test.db.password", "postgres");
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            jdbcUser = postgres.getUsername();
            jdbcPassword = postgres.getPassword();
        }
        dsl = DSL.using(jdbcUrl, jdbcUser, jdbcPassword);

        // Real database-per-tenant with disjoint rows. film_id doubles as the tenant id in the
        // fixture, so tenant 1 holds films 10..11 and tenant 2 film 20 (any values work; the
        // fan-out domain comes from the request, not the rows). language_id points at the
        // fan-out language rows seeded into the default database below (the batched form's
        // untenanted parents).
        for (String db : List.of("fanout_t1", "fanout_t2")) {
            dsl.execute("drop database if exists " + db + " with (force)");
            dsl.execute("create database " + db);
            try (var tenant = DSL.using(tenantUrl(db), jdbcUser, jdbcPassword)) {
                tenant.execute("create table film (film_id int primary key, title text not null,"
                    + " language_id int not null)");
                tenant.execute("create table inventory (inventory_id serial primary key,"
                    + " film_id int not null, store_id int not null)");
                tenant.execute("create table film_actor (actor_id int not null, film_id int not null,"
                    + " primary key (actor_id, film_id))");
            }
        }
        try (var t1 = DSL.using(tenantUrl("fanout_t1"), jdbcUser, jdbcPassword)) {
            t1.execute("insert into film values (11, 'T1 Beta', 901), (10, 'T1 Alpha', 901)");
            t1.execute("insert into inventory (film_id, store_id) values (10, 1), (10, 2)");
            t1.execute("insert into film_actor values (100, 10)");
        }
        try (var t2 = DSL.using(tenantUrl("fanout_t2"), jdbcUser, jdbcPassword)) {
            t2.execute("insert into film values (20, 'T2 Gamma', 902)");
            t2.execute("insert into inventory (film_id, store_id) values (20, 7)");
            t2.execute("insert into film_actor values (200, 20)");
        }
        // The batched form's untenanted parents, in the default database's sakila language table.
        dsl.execute("delete from language where language_id in (901, 902)");
        dsl.execute("insert into language (language_id, name) values (901, 'FanOutLangA'), (902, 'FanOutLangB')");

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
            dsl.execute("delete from language where language_id in (901, 902)");
            dsl.execute("drop database if exists fanout_t1 with (force)");
            dsl.execute("drop database if exists fanout_t2 with (force)");
        }
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void reset() {
        TENANT_2_DOWN.set(false);
        TENANT_1_OPENED.set(0);
        TENANT_2_OPENED.set(0);
    }

    private static ExecutionResult execute(String query, Collection<Integer> fanOutTenants) {
        return graphql.execute(Graphitron.newOwnedExecutionInput("{}", fanOutTenants)
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
            .containsExactly("T1 Alpha", "T1 Beta", "T2 Gamma");
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
    void fannedChildOfUntenantedParent_runsTheBatchedFormAcrossTenants() {
        // The untenanted parents come from the default source; the fanned child fans each parent
        // batch out per tenant, and each language's films land under the right parent (the
        // per-key merge) from the right database (language 901's film lives only in tenant 1).
        var result = execute("{ languages { name films { title } } }", List.of(1, 2));

        assertThat(result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        var languages = (List<Map<String, Object>>) data.get("languages");
        // Order within one parent's list is unspecified here (the batched child declares no
        // @orderBy); the routing proof is which rows arrived, from which database.
        assertThat(filmsOf(languages, "FanOutLangA")).containsExactlyInAnyOrder("T1 Alpha", "T1 Beta");
        assertThat(filmsOf(languages, "FanOutLangB")).containsExactly("T2 Gamma");
    }

    @Test
    void claimedButUnmappedTenant_failsTheRequestBeforeAnySql() {
        var result = execute("{ filmsEverywhere { title } }", List.of(1, 99));

        assertThat(result.getErrors())
            .as("the request's tenant set names an unhosted tenant: a request-level error,"
                + " redacted on the wire (correlation-id reference; the tenant key stays in the"
                + " server log)")
            .anyMatch(e -> e.getMessage().contains("Reference: ") && !e.getMessage().contains("99"));
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

        var result = slowEngine.execute(Graphitron.newOwnedExecutionInput("{}", List.of(1, 2))
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

    static String tenantUrl(String db) {
        return jdbcUrl.replaceAll("/[^/?]+(\\?|$)", "/" + db + "$1");
    }

    private static DataSource defaultDataSource() {
        return dataSource(jdbcUrl, null, null);
    }

    private static DataSource tenantDataSource(String db, AtomicInteger opened, AtomicBoolean down) {
        return dataSource(tenantUrl(db), opened, down);
    }

    /** A DataSource whose acquisition hangs past the scatter deadline, then connects normally. */
    private static DataSource hangingDataSource(String db, long hangMillis) {
        String url = tenantUrl(db);
        return (DataSource) Proxy.newProxyInstance(
            TenantFanOutExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    Thread.sleep(hangMillis);
                    return DriverManager.getConnection(url, jdbcUser, jdbcPassword);
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
                    return DriverManager.getConnection(url, jdbcUser, jdbcPassword);
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
