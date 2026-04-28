package no.sikt.graphitron.rewrite.test;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests that exercise the federation entity dispatcher (the generated
 * {@code EntityFetcherDispatch}) against a real PostgreSQL database. Verifies the
 * runtime path: representation → most-specific resolvable alternative → per-tenant SELECT
 * via {@code VALUES (idx, ...)} derived table → scatter back to original positions.
 */
class FederationEntitiesDispatchTest {

    static PostgreSQLContainer<?> postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final java.util.concurrent.atomic.AtomicInteger QUERY_COUNT = new java.util.concurrent.atomic.AtomicInteger();
    static final List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll
    static void startDatabase() throws Exception {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer<>("postgres:18-alpine")
                .withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.impl.DefaultExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    QUERY_COUNT.incrementAndGet();
                    var sql = ctx.sql();
                    if (sql != null) SQL_LOG.add(sql.toLowerCase(java.util.Locale.ROOT));
                }
            }));
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private GraphitronContext context() {
        return new GraphitronContext() {
            @Override public DSLContext getDslContext(DataFetchingEnvironment env) { return dsl; }
            @Override public <T> T getContextArgument(DataFetchingEnvironment env, String name) { return null; }
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query, Map<String, Object> variables) {
        var input = ExecutionInput.newExecutionInput()
            .query(query)
            .variables(variables)
            .graphQLContext(b -> b.put(GraphitronContext.class, context()))
            .dataLoaderRegistry(new org.dataloader.DataLoaderRegistry())
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    /**
     * Happy path for a {@code @node} type's {@code NODE_ID}-shape alternative: the rep
     * carries a base64 NodeId; the dispatcher decodes it, runs a single SELECT via the
     * derived-table join, and returns the row with the requested fields plus the
     * synthetic {@code __typename}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_resolveSingleNodeIdRep_returnsHydratedRow() {
        String customerId = NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on Customer { firstName lastName } } }",
            Map.of("reps", List.of(Map.of("__typename", "Customer", "id", customerId))));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).containsEntry("__typename", "Customer");
        assertThat(entities.get(0)).containsKey("firstName");
        assertThat(entities.get(0)).containsKey("lastName");
    }

    /**
     * Order preservation under federation's exact-position contract: a single
     * {@code _entities} call with three reps of mixed {@code __typename} returns results
     * in the same order. Carrying {@code idx} through SQL makes order a SQL property,
     * not a Java post-processing step.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_mixedTypenames_preserveOrder() {
        String customer1 = NodeIdEncoder.encode("Customer", 1);
        String film1 = NodeIdEncoder.encode("Film", 1);
        String customer2 = NodeIdEncoder.encode("Customer", 2);

        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename"
            + " ... on Customer { firstName }"
            + " ... on Film { title }"
            + " } }",
            Map.of("reps", List.of(
                Map.of("__typename", "Customer", "id", customer1),
                Map.of("__typename", "Film", "id", film1),
                Map.of("__typename", "Customer", "id", customer2))));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(3);
        assertThat(entities.get(0)).containsEntry("__typename", "Customer");
        assertThat(entities.get(1)).containsEntry("__typename", "Film");
        assertThat(entities.get(2)).containsEntry("__typename", "Customer");
    }

    /**
     * Empty representations list returns an empty list cleanly, with no SELECT issued and
     * no NPE.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_emptyRepresentations_returnsEmptyList() {
        Map<String, Object> data = execute(
            "{ _entities(representations: []) { __typename } }",
            Map.of());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).isEmpty();
    }

    /**
     * Unknown {@code __typename} surfaces a federation-level resolution failure (null
     * entry) rather than an NPE in the dispatcher. Federation lifts this into its own
     * "entity resolution failed" error at the gateway.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_unknownTypename_yieldsNullSlot() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename } }",
            Map.of("reps", List.of(Map.of("__typename", "DoesNotExist", "id", "garbage"))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).isNull();
    }

    /**
     * Garbage NodeId (fails to decode through {@link NodeIdEncoder#decodeValues}) yields a
     * null slot, not an NPE or wrong-row return.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_garbageNodeId_yieldsNullSlot() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename } }",
            Map.of("reps", List.of(Map.of("__typename", "Customer", "id", "not-a-base64-id"))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).isNull();
    }

    /**
     * DIRECT-shape alternative: Film carries {@code @key(fields: "filmId")} and a rep with
     * {@code filmId: 1} resolves through the column-value path (not NodeId decode). Same
     * SQL shape as the NODE_ID path; only the rep-to-column-values translation differs.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_directShapeKey_resolvesViaColumnValue() {
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on Film { title } } }",
            Map.of("reps", List.of(Map.of("__typename", "Film", "filmId", 1))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).containsEntry("__typename", "Film");
        assertThat(entities.get(0)).containsKey("title");
    }

    /**
     * Type-scoped selection set: a single _entities call with two __typenames and inline
     * fragments produces per-type SELECTs whose projection lists exclude the other type's
     * fields. Locks the load-bearing claim that graphql-java's
     * {@code DataFetchingFieldSelectionSet} is type-scoped at the {@code _entities} DFE
     * call site, so {@code <TypeName>.$fields} walking it picks up only the inline
     * fragment scoped to that __typename.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_typeScopedSelectionSet_perTypeProjectionDistinct() {
        String customerId = NodeIdEncoder.encode("Customer", 1);
        String filmId = NodeIdEncoder.encode("Film", 1);
        SQL_LOG.clear();
        execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename"
            + " ... on Customer { firstName lastName }"
            + " ... on Film { title rentalRate }"
            + " } }",
            Map.of("reps", List.of(
                Map.of("__typename", "Customer", "id", customerId),
                Map.of("__typename", "Film", "id", filmId))));

        var customerSelect = SQL_LOG.stream()
            .filter(s -> s.contains("'customer' as \"__typename\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Customer SELECT not found in SQL log: " + SQL_LOG));
        var filmSelect = SQL_LOG.stream()
            .filter(s -> s.contains("'film' as \"__typename\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Film SELECT not found in SQL log: " + SQL_LOG));

        assertThat(customerSelect)
            .as("Customer SELECT projects Customer's fields, not Film's")
            .contains("first_name").contains("last_name")
            .doesNotContain("title").doesNotContain("rental_rate");
        assertThat(filmSelect)
            .as("Film SELECT projects Film's fields, not Customer's")
            .contains("title").contains("rental_rate")
            .doesNotContain("first_name").doesNotContain("last_name");
    }

    /**
     * Multi-tenancy partition: a single {@code _entities} call with two reps of the same
     * __typename whose ids resolve to different tenants issues two SELECTs (one per tenant),
     * not one. The dispatcher's per-rep DFE rebinds {@code arguments} to the rep map so the
     * consumer's {@code getTenantId(repEnv)} resolves against each individual rep.
     */
    @Test
    void entities_multiTenancyPartition_oneSelectPerTenant() {
        String c1 = NodeIdEncoder.encode("Customer", 1);
        String c2 = NodeIdEncoder.encode("Customer", 2);
        Map<String, String> idToTenant = Map.of(c1, "tenantA", c2, "tenantB");

        var perRepTenantContext = new GraphitronContext() {
            @Override public DSLContext getDslContext(DataFetchingEnvironment env) { return dsl; }
            @Override public <T> T getContextArgument(DataFetchingEnvironment env, String name) { return null; }
            @Override public String getTenantId(DataFetchingEnvironment env) {
                String id = env.getArgument("id");
                return id == null ? "" : idToTenant.getOrDefault(id, "");
            }
        };

        var input = ExecutionInput.newExecutionInput()
            .query("query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
                + " __typename ... on Customer { firstName } } }")
            .variables(Map.of("reps", List.of(
                Map.of("__typename", "Customer", "id", c1),
                Map.of("__typename", "Customer", "id", c2))))
            .graphQLContext(b -> b.put(GraphitronContext.class, perRepTenantContext))
            .dataLoaderRegistry(new org.dataloader.DataLoaderRegistry())
            .build();

        QUERY_COUNT.set(0);
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        assertThat(QUERY_COUNT.get())
            .as("two tenants, one Customer SELECT each")
            .isEqualTo(2);
    }

    /**
     * Multi-alternative dispatch: Film carries both a synthesised NODE_ID alternative
     * (from {@code @node}) and a DIRECT alternative (from {@code @key(fields: "filmId")}).
     * Reps that supply only {@code id} pick the NODE_ID alternative; reps that supply only
     * {@code filmId} pick the DIRECT alternative. The dispatcher selects per-rep and
     * groups by alternative, so a mixed call issues two SELECTs.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_multipleAlternatives_dispatchPerRep() {
        String filmId1 = NodeIdEncoder.encode("Film", 1);
        SQL_LOG.clear();
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) {"
            + " __typename ... on Film { title } } }",
            Map.of("reps", List.of(
                Map.of("__typename", "Film", "id", filmId1),
                Map.of("__typename", "Film", "filmId", 2))));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(2);
        assertThat(entities.get(0)).containsEntry("__typename", "Film");
        assertThat(entities.get(1)).containsEntry("__typename", "Film");

        long filmSelectCount = SQL_LOG.stream()
            .filter(s -> s.contains("'film' as \"__typename\""))
            .count();
        assertThat(filmSelectCount)
            .as("two alternatives → two SELECTs")
            .isEqualTo(2);
    }

    /**
     * Empty selection-set sanity: querying only {@code __typename} on an entity returns
     * the type discriminator without requesting any actual columns. The synthetic
     * {@code __typename} literal is always projected, so the rep still resolves.
     */
    @Test
    @SuppressWarnings("unchecked")
    void entities_typenameOnly_resolvesWithoutFieldProjection() {
        String customerId = NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "query Q($reps: [_Any!]!) { _entities(representations: $reps) { __typename } }",
            Map.of("reps", List.of(Map.of("__typename", "Customer", "id", customerId))));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) data.get("_entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)).containsEntry("__typename", "Customer");
    }
}
