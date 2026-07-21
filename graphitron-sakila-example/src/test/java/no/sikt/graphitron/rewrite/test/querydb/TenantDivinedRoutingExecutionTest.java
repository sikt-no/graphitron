package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.multitenant.Graphitron;
import no.sikt.graphitron.generated.multitenant.schema.GraphitronRuntime;
import no.sikt.graphitron.generated.multitenant.util.NodeIdEncoder;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof of operation-divined tenant routing against real PostgreSQL, over the
 * multi-tenant fixture package (compiled with {@code <tenantColumn>film_id</tenantColumn>}; see
 * {@code multitenant.graphqls}). Database-per-tenant is real here: two extra databases
 * ({@code tenant_1}, {@code tenant_2}) each carry the tenant-scoped tables with disjoint rows,
 * behind counting {@code DataSource}s keyed by the <em>typed</em> tenant key (the
 * {@code Map<Integer, DataSource>} below compiling against the generated constructor is itself
 * the typed-key proof); the container's main database is the default source, serving the global
 * {@code language} reference data.
 *
 * <p>The sibling {@code TenantRoutingExecutionTest} proved routing given a caller-known tenant
 * (test-supplied keys, no divination); this class proves the divined bindings: the tenant is
 * read off the operation itself, per the classified {@code TenantBinding} arm, with no tenant
 * parameter anywhere in the request.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class TenantDivinedRoutingExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;                 // default database, out-of-band setup/assertions
    static String jdbcUrl, jdbcUser, jdbcPassword;

    static final AtomicInteger TENANT_1_OPENED = new AtomicInteger();
    static final AtomicInteger TENANT_2_OPENED = new AtomicInteger();
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

        // Real database-per-tenant: the generated SQL schema-qualifies table names
        // ("public"."film"), so isolation comes from separate databases, not search_path.
        for (String db : List.of("tenant_1", "tenant_2")) {
            dsl.execute("drop database if exists " + db + " with (force)");
            dsl.execute("create database " + db);
            try (var tenant = DSL.using(tenantUrl(db), jdbcUser, jdbcPassword)) {
                tenant.execute("create table film (film_id int primary key, title text not null)");
                tenant.execute("create table inventory (inventory_id serial primary key,"
                    + " film_id int not null, store_id int not null)");
                tenant.execute("create table film_actor (actor_id int not null, film_id int not null,"
                    + " primary key (actor_id, film_id))");
            }
        }
        try (var t1 = DSL.using(tenantUrl("tenant_1"), jdbcUser, jdbcPassword)) {
            t1.execute("insert into film values (1, 'Tenant One Film')");
            t1.execute("insert into inventory (film_id, store_id) values (1, 1), (1, 2)");
            t1.execute("insert into film_actor values (10, 1)");
        }
        try (var t2 = DSL.using(tenantUrl("tenant_2"), jdbcUser, jdbcPassword)) {
            t2.execute("insert into film values (2, 'Tenant Two Film')");
            t2.execute("insert into film_actor values (20, 2)");
        }

        // Typed tenant key: Map<Integer, DataSource> compiles against the generated constructor
        // because the catalog's film_id column types every tenant-keyed surface.
        Map<Integer, DataSource> byTenant = Map.of(
            1, countingDataSource("tenant_1", TENANT_1_OPENED),
            2, countingDataSource("tenant_2", TENANT_2_OPENED));
        var runtime = new GraphitronRuntime(
            countingDataSource(null, null), byTenant, SQLDialect.POSTGRES);
        graphql = runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (dsl != null) {
            dsl.execute("drop database if exists tenant_1 with (force)");
            dsl.execute("drop database if exists tenant_2 with (force)");
        }
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void resetCounters() {
        TENANT_1_OPENED.set(0);
        TENANT_2_OPENED.set(0);
    }

    private static ExecutionResult execute(String query) {
        return graphql.execute(Graphitron.newOwnedExecutionInput("{\"sub\":\"test-user\"}")
            .query(query).build());
    }

    // ===== ArgumentBound: the filter argument routes the whole subtree =====

    @Test
    void argumentBound_sameQueryTwoTenantValues_seesDisjointRows() {
        var one = execute("{ films(filmId: 1) { title } }");
        assertThat(one.getErrors()).as("errors: " + one.getErrors()).isEmpty();
        assertThat(((Map<String, Object>) one.getData()).get("films"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Map.class))
            .extracting(m -> m.get("title"))
            .containsExactly("Tenant One Film");

        var two = execute("{ films(filmId: 2) { title } }");
        assertThat(two.getErrors()).isEmpty();
        assertThat(((Map<String, Object>) two.getData()).get("films"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Map.class))
            .extracting(m -> m.get("title"))
            .containsExactly("Tenant Two Film");
    }

    @Test
    void inheritedChild_routesItsBatchToTheDivinedTenant() {
        var result = execute("{ films(filmId: 1) { title inventories { inventoryId } } }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        var films = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("films");
        assertThat(films).hasSize(1);
        assertThat((List<Map<String, Object>>) films.get(0).get("inventories"))
            .as("the batched child reads tenant 1's rows through the handed-down tenant")
            .hasSize(2);
        assertThat(TENANT_2_OPENED.get())
            .as("nothing in the operation touches the other tenant's database")
            .isZero();
    }

    // ===== Untenanted: global reference data stays on the default source =====

    @Test
    void untenanted_readsTheDefaultSource_touchingNoTenantDatabase() {
        var result = execute("{ languages { name } }");
        assertThat(result.getErrors()).isEmpty();
        assertThat((List<?>) ((Map<String, Object>) result.getData()).get("languages")).isNotEmpty();
        assertThat(TENANT_1_OPENED.get() + TENANT_2_OPENED.get())
            .as("global reference data acquires only the default source")
            .isZero();
    }

    // ===== Unknown divined tenant: request-level error before any SQL =====

    @Test
    void unknownDivinedTenant_errorsBeforeAnyTenantAcquisition() {
        var result = execute("{ films(filmId: 99) { title } }");
        assertThat(result.getErrors())
            .as("an unconfigured tenant key is a request-level error")
            .isNotEmpty();
        // films is [Film!]!, so the field error nulls the whole data payload.
        Map<String, Object> data = result.getData();
        assertThat(data == null ? null : data.get("films")).isNull();
        assertThat(TENANT_1_OPENED.get() + TENANT_2_OPENED.get())
            .as("the error fires before any tenant connection is acquired")
            .isZero();
    }

    // ===== ArgumentBound mutation: the input's tenant field routes the write =====

    @Test
    void mutation_routedByItsInputTenantField_writesOnlyThatTenantsDatabase() {
        var result = execute(
            "mutation { createInventory(in: { filmId: 2, storeId: 7 }) { inventoryId } }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat((Map<String, Object>) ((Map<String, Object>) result.getData()).get("createInventory"))
            .extracting(m -> m.get("inventoryId"))
            .isNotNull();

        try (var t2 = DSL.using(tenantUrl("tenant_2"), jdbcUser, jdbcPassword)) {
            assertThat(t2.fetchCount(DSL.table("inventory"), DSL.field("store_id").eq(7)))
                .as("the write landed in tenant 2's database")
                .isEqualTo(1);
            t2.execute("delete from inventory where store_id = 7");
        }
        try (var t1 = DSL.using(tenantUrl("tenant_1"), jdbcUser, jdbcPassword)) {
            assertThat(t1.fetchCount(DSL.table("inventory"), DSL.field("store_id").eq(7)))
                .as("no cross-tenant write")
                .isZero();
        }
        assertThat(TENANT_1_OPENED.get()).isZero();
    }

    // ===== NodeIdBound: a nodes(ids:) batch spanning tenants partitions per decoded tenant =====

    @Test
    void nodes_batchSpanningTenants_partitionsPerDecodedTenant() {
        String t1Actor = NodeIdEncoder.encodeFilmActor(10, 1);
        String t2Actor = NodeIdEncoder.encodeFilmActor(20, 2);

        var result = execute("{ nodes(ids: [\"" + t1Actor + "\", \"" + t2Actor + "\"]) {"
            + " ... on FilmActor { actorId } } }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat((List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("nodes"))
            .as("each id resolves against its own tenant's database; a batch routed to one"
                + " tenant would null the other's slot")
            .extracting(m -> m == null ? null : m.get("actorId"))
            .containsExactly(10, 20);
        assertThat(TENANT_1_OPENED.get())
            .as("one tenant-homogeneous group pins one connection on tenant 1")
            .isEqualTo(1);
        assertThat(TENANT_2_OPENED.get())
            .as("one tenant-homogeneous group pins one connection on tenant 2")
            .isEqualTo(1);
    }

    // ===== helpers =====

    private static String tenantUrl(String database) {
        return jdbcUrl.replaceFirst("(jdbc:postgresql://[^/]+/)[^?]*", "$1" + database);
    }

    /**
     * A DataSource over one database, optionally counting acquisitions. {@code database} null
     * targets the container's main database (the default source).
     */
    private static DataSource countingDataSource(String database, AtomicInteger counter) {
        String url = database == null ? jdbcUrl : tenantUrl(database);
        return (DataSource) Proxy.newProxyInstance(
            TenantDivinedRoutingExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    if (counter != null) counter.incrementAndGet();
                    return DriverManager.getConnection(url, jdbcUser, jdbcPassword);
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "tenantDataSource:" + (database == null ? "default" : database);
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    }
}
