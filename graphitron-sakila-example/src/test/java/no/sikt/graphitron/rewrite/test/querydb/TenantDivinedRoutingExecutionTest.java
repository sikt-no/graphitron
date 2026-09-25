package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.multitenant.Graphitron;
import no.sikt.graphitron.generated.multitenant.schema.GraphitronRuntime;
import no.sikt.graphitron.generated.multitenant.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.services.SakilaTenantSessionIdentity;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
                tenant.execute("create table film_actor_note (actor_id int not null, film_id int not null,"
                    + " lang_code varchar(3) not null, note_txt varchar(255),"
                    + " primary key (actor_id, film_id, lang_code))");
                tenant.execute("create table film_scene (film_id int not null, scene_no int not null,"
                    + " parent_scene_no int, label varchar(100), primary key (film_id, scene_no),"
                    + " constraint film_scene_parent_fk foreign key (film_id, parent_scene_no)"
                    + " references film_scene (film_id, scene_no))");
                TenantSessionFixture.installSessionObjects(tenant);
            }
        }
        try (var t1 = DSL.using(tenantUrl("tenant_1"), jdbcUser, jdbcPassword)) {
            t1.execute("insert into film values (1, 'Tenant One Film')");
            t1.execute("insert into inventory (film_id, store_id) values (1, 1), (1, 2)");
            t1.execute("insert into film_actor values (10, 1)");
            t1.execute("insert into film_scene (film_id, scene_no) values (1, 1)");
        }
        try (var t2 = DSL.using(tenantUrl("tenant_2"), jdbcUser, jdbcPassword)) {
            t2.execute("insert into film values (2, 'Tenant Two Film')");
            t2.execute("insert into film_actor values (20, 2)");
            t2.execute("insert into film_actor_note values (20, 2, 'nob', 'Before')");
            t2.execute("insert into film_scene (film_id, scene_no) values (2, 1), (2, 2), (2, 3)");
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

    @AfterEach
    void resetScenes() {
        for (String db : List.of("tenant_1", "tenant_2")) {
            try (var tenant = DSL.using(tenantUrl(db), jdbcUser, jdbcPassword)) {
                tenant.execute("update film_scene set parent_scene_no = null, label = null");
            }
        }
    }

    private static ExecutionResult execute(String query) {
        // The request tenant set admits both hosted tenants, so these tests see routing alone.
        return execute(query, List.of(1, 2));
    }

    private static ExecutionResult execute(String query, java.util.Collection<Integer> tenants) {
        return executeAs(query, tenants, "test-user");
    }

    private static ExecutionResult executeAs(String query, java.util.Collection<Integer> tenants, String sub) {
        return graphql.execute(Graphitron.newOwnedExecutionInput(tenants, "{\"sub\":\"" + sub + "\"}")
            .query(query).build());
    }

    /** The tenants this test's own mounts received, told apart from concurrent classes by {@code sub}. */
    private static List<java.util.Optional<Integer>> mountedTenants(String sub) {
        return SakilaTenantSessionIdentity.MOUNTS.stream()
            .filter(m -> sub.equals(m.sub()))
            .map(SakilaTenantSessionIdentity.Mount::tenant)
            .toList();
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

    // ===== ROUTED carrier: the connection launcher's carrier rides the routed dsl =====

    @Test
    void argumentBoundConnection_totalCountAggregatesOnTheDivinedTenantSource() {
        var result = execute(
            "{ filmsConnectionScoped(filmId: 2, first: 10) { totalCount edges { node { title } } } }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        var connection = (Map<String, Object>)
            ((Map<String, Object>) result.getData()).get("filmsConnectionScoped");
        assertThat((List<Map<String, Object>>) connection.get("edges"))
            .extracting(e -> ((Map<String, Object>) e.get("node")).get("title"))
            .containsExactly("Tenant Two Film");
        assertThat(connection.get("totalCount"))
            .as("the lazy aggregate runs on the routed dsl the carrier carries, against the "
                + "divined tenant's rows under the page predicate")
            .isEqualTo(1);
        assertThat(TENANT_1_OPENED.get())
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
        // Tenant 99 is in the request set but hosted by no DataSource here: the hosting check.
        var result = execute("{ films(filmId: 99) { title } }", List.of(1, 2, 99));
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

    // ===== The request tenant set: routing only to tenants the request may touch =====

    @Test
    void argumentRoutedTenantOutsideTheSet_isRefused_andItsDatabaseNeverOpened() {
        var result = execute("{ films(filmId: 2) { title } }", List.of(1));
        assertThat(result.getErrors())
            .as("the caller named a tenant explicitly, so the refusal is an error, surfaced to the client"
                + " (films is [Film!]!, so graphql-java adds its non-null bubbling error beside it)")
            .anySatisfy(e -> assertThat(e.getMessage()).contains("'2' is not permitted for this request"));
        assertThat(TENANT_2_OPENED.get()).as("no connection to a refused tenant is taken").isZero();
    }

    @Test
    void unhostedTenantOutsideTheSet_getsTheSameRefusal_soHostingCannotBeProbed() {
        var result = execute("{ films(filmId: 99) { title } }", List.of(1));
        assertThat(result.getErrors())
            .anySatisfy(e -> assertThat(e.getMessage()).contains("'99' is not permitted for this request"))
            .noneSatisfy(e -> assertThat(e.getMessage()).contains("No source configured"));
    }

    @Test
    void nodeIdArgumentDecodedToATenantOutsideTheSet_isRefused() {
        var result = execute("mutation { updateFilmByNodeId(in: { id: \""
            + NodeIdEncoder.encodeFilm(2) + "\", title: \"Never Written\" }) { title } }", List.of(1));
        assertThat(result.getErrors())
            .as("a decoded String key and the set's boxed Integer compare equal, so this is the refusal,"
                + " not a silent pass")
            .singleElement()
            .satisfies(e -> assertThat(e.getMessage()).contains("'2' is not permitted for this request"));
        assertThat(TENANT_2_OPENED.get()).isZero();
        try (var t2 = DSL.using(tenantUrl("tenant_2"), jdbcUser, jdbcPassword)) {
            assertThat(t2.fetchValue("select title from film where film_id = 2")).isEqualTo("Tenant Two Film");
        }
    }

    @Test
    void node_idOfATenantOutsideTheSet_answersNull_likeAnUnknownId() {
        var result = execute("{ node(id: \"" + NodeIdEncoder.encodeFilm(2) + "\") { id } }", List.of(1));
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat(((Map<String, Object>) result.getData()).get("node")).isNull();
        assertThat(TENANT_2_OPENED.get()).isZero();
    }

    @Test
    void nodes_batchSpanningTheSetsEdge_resolvesThePermittedTenant_andNullsTheOther() {
        String t1Actor = NodeIdEncoder.encodeFilmActor(10, 1);
        String t2Actor = NodeIdEncoder.encodeFilmActor(20, 2);

        var result = execute("{ nodes(ids: [\"" + t1Actor + "\", \"" + t2Actor + "\"]) {"
            + " ... on FilmActor { actorId } } }", List.of(1));
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat((List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("nodes"))
            .extracting(m -> m == null ? null : m.get("actorId"))
            .containsExactly(10, null);
        assertThat(TENANT_1_OPENED.get()).isEqualTo(1);
        assertThat(TENANT_2_OPENED.get()).as("the refused group takes no connection").isZero();
    }

    @Test
    void operationBuiltWithoutTheSet_failsBeforeAnyDatabaseIsOpened() {
        // An ExecutionInput assembled by hand rather than through the generated factory: no
        // request tenant set, and the set has no unrestricted meaning.
        var input = ExecutionInput.newExecutionInput()
            .query("{ films(filmId: 1) { title } }")
            .graphQLContext(b -> b.put("claims", "{\"sub\":\"test-user\"}"))
            .build();
        Throwable failure = null;
        ExecutionResult result = null;
        try {
            result = graphql.execute(input);
        } catch (RuntimeException e) {
            failure = e;
        }
        if (failure != null) {
            assertThat(failure).hasStackTraceContaining("No request tenant set");
        } else {
            assertThat(result.getErrors().toString()).contains("No request tenant set");
        }
        assertThat(TENANT_1_OPENED.get() + TENANT_2_OPENED.get()).isZero();
    }

    // ===== The tenant slot: each mount learns the tenant it is mounting for =====

    @Test
    void mount_receivesEachRoutedTenant_andEmptyForTheDefaultSource() {
        String sub = "mount-probe-" + java.util.UUID.randomUUID();
        var result = executeAs("{ a: films(filmId: 1) { title } b: films(filmId: 2) { title }"
            + " languages { name } }", List.of(1, 2), sub);
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat(mountedTenants(sub))
            .as("one mount per pinned connection, each told its own tenant")
            .containsExactlyInAnyOrder(java.util.Optional.of(1), java.util.Optional.of(2), java.util.Optional.empty());
    }

    @Test
    void mount_tenantsSharingOneDataSource_getOneMountEach_withTheirOwnKey() {
        DataSource shared = countingDataSource("tenant_1", null);
        Map<Integer, DataSource> byTenant = Map.of(1, shared, 2, shared);
        var sharedEngine = new GraphitronRuntime(countingDataSource(null, null), byTenant, SQLDialect.POSTGRES)
            .newGraphQL(Graphitron.buildSchema(b -> {})).build();
        String sub = "mount-probe-" + java.util.UUID.randomUUID();

        var result = sharedEngine.execute(Graphitron.newOwnedExecutionInput(List.of(1, 2),
                "{\"sub\":\"" + sub + "\"}")
            .query("{ a: films(filmId: 1) { title } b: films(filmId: 2) { title } }").build());
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat(mountedTenants(sub))
            .as("session identity is per (connection, tenant): two keys on one pool are two mounts")
            .containsExactlyInAnyOrder(java.util.Optional.of(1), java.util.Optional.of(2));
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

    // ===== Decoded-slot routing: the tenant sits inside the node id the write already decodes =====

    @Test
    void bulkDeleteByNodeId_idsAgreeingOnOneTenant_deletesOnlyThere() {
        try (var t1 = DSL.using(tenantUrl("tenant_1"), jdbcUser, jdbcPassword)) {
            t1.execute("insert into film_actor values (11, 1), (12, 1)");
        }
        String first = NodeIdEncoder.encodeFilmActor(11, 1);
        String second = NodeIdEncoder.encodeFilmActor(12, 1);

        var result = execute("mutation { deleteFilmActorsByNodeId(in: ["
            + "{ id: \"" + first + "\" }, { id: \"" + second + "\" }]) }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat((List<?>) ((Map<String, Object>) result.getData()).get("deleteFilmActorsByNodeId"))
            .as("the mutation names no tenant; the routing decodes it out of the ids")
            .hasSize(2);
        assertThat(TENANT_2_OPENED.get())
            .as("the tenant is slot 1 of the decoded key, so the other database is never opened")
            .isZero();

        try (var t1 = DSL.using(tenantUrl("tenant_1"), jdbcUser, jdbcPassword)) {
            assertThat(t1.fetchCount(DSL.table("film_actor"),
                    DSL.field("actor_id").in(11, 12)))
                .as("the rows went from the divined tenant's database")
                .isZero();
        }
    }

    @Test
    void bulkDeleteByNodeId_idsMixingTenants_refusedBeforeAnySql() {
        String t1Actor = NodeIdEncoder.encodeFilmActor(10, 1);
        String t2Actor = NodeIdEncoder.encodeFilmActor(20, 2);

        var result = execute("mutation { deleteFilmActorsByNodeId(in: ["
            + "{ id: \"" + t1Actor + "\" }, { id: \"" + t2Actor + "\" }]) }");
        assertThat(result.getErrors())
            .as("a write is one statement on one connection, so a batch spanning tenants has no"
                + " correct execution and is refused rather than partitioned")
            .isNotEmpty();
        assertThat(result.getErrors().toString())
            .as("the refusal names the disagreement")
            .contains("disagree");
        assertThat(TENANT_1_OPENED.get() + TENANT_2_OPENED.get())
            .as("the disagreement is found before any connection is acquired")
            .isZero();

        try (var t1 = DSL.using(tenantUrl("tenant_1"), jdbcUser, jdbcPassword)) {
            assertThat(t1.fetchCount(DSL.table("film_actor"), DSL.field("actor_id").eq(10)))
                .as("neither tenant's row was touched").isEqualTo(1);
        }
        try (var t2 = DSL.using(tenantUrl("tenant_2"), jdbcUser, jdbcPassword)) {
            assertThat(t2.fetchCount(DSL.table("film_actor"), DSL.field("actor_id").eq(20)))
                .isEqualTo(1);
        }
    }

    @Test
    void malformedNodeId_surfacesTheOneDecodeFailureMessage() {
        var result = execute(
            "mutation { deleteFilmActorByNodeId(in: { id: \"not-a-node-id\" }) }");
        assertThat(result.getErrors())
            .as("routing decodes before the carrier does, so its failure is what the client sees;"
                + " it must be the same message the carrier's own decode would have raised")
            .isNotEmpty();
        assertThat(result.getErrors().toString())
            .contains("not a valid FilmActor id");
        assertThat(TENANT_1_OPENED.get() + TENANT_2_OPENED.get()).isZero();
    }

    @Test
    void updateKeyedByNodeId_routesOnTheArityOneDecodedKey() {
        var result = execute("mutation { updateFilmByNodeId(in: { id: \""
            + NodeIdEncoder.encodeFilm(2) + "\", title: \"Retitled Two\" }) { title } }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat((Map<String, Object>) ((Map<String, Object>) result.getData()).get("updateFilmByNodeId"))
            .extracting(m -> m.get("title")).isEqualTo("Retitled Two");
        assertThat(TENANT_1_OPENED.get())
            .as("the decoded key is the tenant itself; the other database is never opened")
            .isZero();

        try (var t2 = DSL.using(tenantUrl("tenant_2"), jdbcUser, jdbcPassword)) {
            t2.execute("update film set title = 'Tenant Two Film' where film_id = 2");
        }
    }

    @Test
    void updateKeyedByNodeId_routesOnTheCompositeKeysMiddleSlot() {
        var result = execute("mutation { updateFilmActorNoteByNodeId(in: { id: \""
            + NodeIdEncoder.encodeFilmActorNote(20, 2, "nob") + "\", noteTxt: \"After\" }) }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat(TENANT_1_OPENED.get())
            .as("the tenant is slot 1 of a three-column key; the other database is never opened")
            .isZero();

        try (var t2 = DSL.using(tenantUrl("tenant_2"), jdbcUser, jdbcPassword)) {
            assertThat(t2.fetchValue("select note_txt from film_actor_note where actor_id = 20"))
                .as("the row was updated in the divined tenant's database").isEqualTo("After");
            t2.execute("update film_actor_note set note_txt = 'Before' where actor_id = 20");
        }
    }

    @Test
    void insertWithFkTargetNodeIdReference_routesOnTheLiftedColumn() {
        var result = execute("mutation { createInventoryByFilmRef(in: { filmRef: \""
            + NodeIdEncoder.encodeFilm(2) + "\", storeId: 9 }) { inventoryId } }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();

        try (var t2 = DSL.using(tenantUrl("tenant_2"), jdbcUser, jdbcPassword)) {
            assertThat(t2.fetchCount(DSL.table("inventory"), DSL.field("store_id").eq(9)))
                .as("the decoded Film key lifted onto inventory.film_id, which is the tenant")
                .isEqualTo(1);
            t2.execute("delete from inventory where store_id = 9");
        }
        assertThat(TENANT_1_OPENED.get()).isZero();
    }

    @Test
    void readFilteredByNodeIds_routesOnTheDecodedSlot() {
        var result = execute("{ filmActorsByNodeId(ids: [\""
            + NodeIdEncoder.encodeFilmActor(20, 2) + "\"]) { actorId } }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat((List<Map<String, Object>>)
                ((Map<String, Object>) result.getData()).get("filmActorsByNodeId"))
            .extracting(m -> m.get("actorId"))
            .containsExactly(20);
        assertThat(TENANT_1_OPENED.get())
            .as("before the projection axis this handed the base64 id to the tenant lookup and"
                + " failed at request time")
            .isZero();
    }

    // ===== A self-FK reference whose key embeds the tenant: co-bound, agreement-checked =====

    @Test
    void updateSceneParent_sameTenantParent_repointsTheRowInItsOwnTenant() {
        var result = execute("mutation { updateFilmSceneParent(in: { id: \""
            + NodeIdEncoder.encodeFilmScene(2, 2) + "\", parent: \""
            + NodeIdEncoder.encodeFilmScene(2, 1) + "\" }) }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat(TENANT_1_OPENED.get())
            .as("id and parent agree on tenant 2; the other database is never opened")
            .isZero();

        assertThat(tenant2SceneParent(2)).isEqualTo(1);
    }

    @Test
    void updateSceneParent_crossTenantParent_refusedBeforeAnyConnection() {
        var result = execute("mutation { updateFilmSceneParent(in: { id: \""
            + NodeIdEncoder.encodeFilmScene(2, 2) + "\", parent: \""
            + NodeIdEncoder.encodeFilmScene(1, 1) + "\" }) }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().toString())
            .as("the tenant fold refuses it, not the per-tenant foreign key")
            .contains("Tenant bindings disagree");
        assertThat(TENANT_1_OPENED.get() + TENANT_2_OPENED.get())
            .as("the parent's tenant co-binds, so the disagreement precedes acquisition")
            .isZero();

        assertThat(tenant2SceneParent(2)).isNull();
    }

    @Test
    void updateSceneParent_omittedParent_routesOnTheIdAlone() {
        var result = execute("mutation { updateFilmSceneParent(in: { id: \""
            + NodeIdEncoder.encodeFilmScene(2, 2) + "\", label: \"x\" }) }");
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat(TENANT_1_OPENED.get()).isZero();

        try (var t2 = DSL.using(tenantUrl("tenant_2"), jdbcUser, jdbcPassword)) {
            assertThat(t2.fetchValue("select label from film_scene where film_id = 2 and scene_no = 2"))
                .isEqualTo("x");
        }
    }

    @Test
    void bulkUpdateSceneParents_anyRowNamingAnotherTenant_refusesTheWholeCall() {
        var result = execute("mutation { updateFilmSceneParents(in: ["
            + "{ id: \"" + NodeIdEncoder.encodeFilmScene(2, 2) + "\", parent: \""
            + NodeIdEncoder.encodeFilmScene(2, 1) + "\" },"
            + "{ id: \"" + NodeIdEncoder.encodeFilmScene(2, 3) + "\", parent: \""
            + NodeIdEncoder.encodeFilmScene(1, 1) + "\" }]) }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().toString()).contains("Tenant bindings disagree");
        assertThat(TENANT_1_OPENED.get() + TENANT_2_OPENED.get()).isZero();
        assertThat(tenant2SceneParent(2)).as("neither row changed").isNull();
        assertThat(tenant2SceneParent(3)).isNull();

        var agreeing = execute("mutation { updateFilmSceneParents(in: ["
            + "{ id: \"" + NodeIdEncoder.encodeFilmScene(2, 2) + "\", parent: \""
            + NodeIdEncoder.encodeFilmScene(2, 1) + "\" },"
            + "{ id: \"" + NodeIdEncoder.encodeFilmScene(2, 3) + "\", parent: \""
            + NodeIdEncoder.encodeFilmScene(2, 1) + "\" }]) }");
        assertThat(agreeing.getErrors()).as("errors: " + agreeing.getErrors()).isEmpty();
        assertThat(TENANT_1_OPENED.get()).isZero();
        assertThat(tenant2SceneParent(2)).isEqualTo(1);
        assertThat(tenant2SceneParent(3)).isEqualTo(1);
    }

    // ===== helpers =====

    private static Object tenant2SceneParent(int sceneNo) {
        try (var t2 = DSL.using(tenantUrl("tenant_2"), jdbcUser, jdbcPassword)) {
            return t2.fetchValue(
                "select parent_scene_no from film_scene where film_id = 2 and scene_no = " + sceneNo);
        }
    }

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
