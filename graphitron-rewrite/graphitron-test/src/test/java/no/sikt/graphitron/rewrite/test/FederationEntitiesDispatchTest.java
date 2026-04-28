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
}
