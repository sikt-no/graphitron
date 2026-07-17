package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof that a Relay connection projects the <em>union</em> of sub-selections when
 * the same result key is selected under both {@code edges { node { ... } }} and {@code nodes}.
 *
 * <p>graphql-java's {@code getFieldsGroupedByResultKey()} flattens the whole subtree, so the two
 * paths collapse into one bucket per result key. The generated {@code <Node>.$fields} loop used to
 * descend into only the first occurrence's sub-selection; any reference sub-field requested under
 * only the other path was missing from the SELECT, and its reader failed per row with a jOOQ
 * "Field ... is not contained in row type" error (surfacing as field errors + silent {@code null}
 * data on the diverging side). These tests pin the fixed behaviour in all four divergence
 * directions, one level deep, and through a polymorphic connection, plus the two fail-loud guards
 * for divergence the union cannot represent: occurrences that disagree on the underlying field
 * name (checked universally per bucket) or on arguments (checked in arms that consume them).
 */
@ExecutionTier
class ConnectionSharedResultKeyProjectionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine")
                .withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private graphql.ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        return graphql.execute(input);
    }

    // ===== The four divergence directions on an inline reference field (Store.customers) =====
    //
    // Store 1 owns customers Mary Smith, Patricia Johnson, Barbara Jones (init.sql). Each case
    // asserts the concrete values on BOTH sides, so a regression to first-occurrence projection
    // (null data + field error on the diverging side) cannot pass.

    @SuppressWarnings("unchecked")
    private void assertStore1CustomersBothSides(String edgesSelection, String nodesSelection,
            String expectedEdgeField, String expectedNodeField) {
        Map<String, Object> data = execute("""
            { stores { edges { node { storeId customers { %s } } } nodes { storeId customers { %s } } } }
            """.formatted(edgesSelection, nodesSelection));

        var conn = (Map<String, Object>) data.get("stores");
        var edges = (List<Map<String, Object>>) conn.get("edges");
        var nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(edges).hasSize(2);
        assertThat(nodes).hasSize(2);

        var edgeCustomers = (List<Map<String, Object>>) ((Map<String, Object>) edges.get(0).get("node")).get("customers");
        assertThat(edgeCustomers).extracting(c -> c.get(expectedEdgeField))
            .containsExactly(expectedEdgeField.equals("firstName")
                ? new Object[] {"Mary", "Patricia", "Barbara"}
                : new Object[] {"Smith", "Johnson", "Jones"});

        var nodeCustomers = (List<Map<String, Object>>) nodes.get(0).get("customers");
        assertThat(nodeCustomers).extracting(c -> c.get(expectedNodeField))
            .containsExactly(expectedNodeField.equals("firstName")
                ? new Object[] {"Mary", "Patricia", "Barbara"}
                : new Object[] {"Smith", "Johnson", "Jones"});
    }

    @Test
    void referenceUnderBothPaths_disjointSelections_bothSidesResolve() {
        assertStore1CustomersBothSides("firstName", "lastName", "firstName", "lastName");
    }

    @Test
    void referenceUnderBothPaths_edgesSubsetOfNodes_bothSidesResolve() {
        assertStore1CustomersBothSides("firstName", "firstName lastName", "firstName", "lastName");
    }

    @Test
    void referenceUnderBothPaths_nodesSubsetOfEdges_bothSidesResolve() {
        assertStore1CustomersBothSides("firstName lastName", "lastName", "firstName", "lastName");
    }

    @Test
    void referenceUnderBothPaths_identicalSelections_behaviourUnchanged() {
        assertStore1CustomersBothSides("firstName", "firstName", "firstName", "firstName");
    }

    // ===== Divergence one level down (NestingField -> inline reference) =====

    @Test
    @SuppressWarnings("unchecked")
    void deepNesting_divergenceOneLevelDown_bothSidesResolve() {
        // The diverging bucket is `address` inside the merged `location` descent: edges asks for
        // district, nodes for the street address. Store 1 -> address 1 (47 MySakila Drive, Alberta).
        Map<String, Object> data = execute("""
            { stores {
                edges { node { location { address { district } } } }
                nodes { location { address { address } } }
            } }
            """);

        var conn = (Map<String, Object>) data.get("stores");
        var edges = (List<Map<String, Object>>) conn.get("edges");
        var edgeAddress = (Map<String, Object>) ((Map<String, Object>)
            ((Map<String, Object>) edges.get(0).get("node")).get("location")).get("address");
        assertThat(edgeAddress.get("district")).isEqualTo("Alberta");

        var nodes = (List<Map<String, Object>>) conn.get("nodes");
        var nodeAddress = (Map<String, Object>) ((Map<String, Object>) nodes.get(0).get("location")).get("address");
        assertThat(nodeAddress.get("address")).isEqualTo("47 MySakila Drive");
    }

    // ===== Polymorphic connection (multi-table Searchable union of Film + Actor) =====

    @Test
    @SuppressWarnings("unchecked")
    void polymorphicConnection_divergentNestedSelections_bothSidesResolve() {
        // The diverging bucket is Film's `summary` NestingField inside the restrictTo-filtered
        // selection: edges asks for summary.title, nodes for summary.releaseYear (all seed films
        // are 2006). The restrictTo view preserves full occurrence lists per key, so one fix at
        // the $fields loop covers this path too.
        Map<String, Object> data = execute("""
            { searchConnection(first: 8) {
                edges { node { __typename ... on Film { summary { title } } } }
                nodes { __typename ... on Film { summary { releaseYear } } }
            } }
            """);

        var conn = (Map<String, Object>) data.get("searchConnection");
        var edgeFilmSummaries = ((List<Map<String, Object>>) conn.get("edges")).stream()
            .map(e -> (Map<String, Object>) e.get("node"))
            .filter(n -> n.get("__typename").equals("Film"))
            .map(n -> (Map<String, Object>) n.get("summary"))
            .toList();
        assertThat(edgeFilmSummaries).hasSize(5)
            .allSatisfy(s -> assertThat(s.get("title")).isNotNull());

        var nodeFilmSummaries = ((List<Map<String, Object>>) conn.get("nodes")).stream()
            .filter(n -> n.get("__typename").equals("Film"))
            .map(n -> (Map<String, Object>) n.get("summary"))
            .toList();
        assertThat(nodeFilmSummaries).hasSize(5)
            .allSatisfy(s -> assertThat(s.get("releaseYear")).isEqualTo(2006));
    }

    // ===== Fail-loud guards =====

    @Test
    void argumentDivergence_onArgConsumingArm_failsLoudAsFieldError() {
        // customersFirstN reads its `first` argument off the SelectedField; edges and nodes sit in
        // sibling selection sets GraphQL field-merging validation never compares, so divergent
        // arguments are legal at the GraphQL layer and must fail loud here instead of silently
        // serving the first occurrence's limit for both paths.
        var result = executeRaw("""
            { stores {
                edges { node { customersFirstN(first: 1) { firstName } } }
                nodes { customersFirstN(first: 2) { firstName } }
            } }
            """);

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .contains("customersFirstN")
            .contains("conflicting arguments");
    }

    @Test
    @SuppressWarnings("unchecked")
    void argumentAgreement_onArgConsumingArm_passesGuardAndResolves() {
        // Same argument on both paths: the guard compares equal maps and the arm serves the
        // canonical occurrence's limit.
        Map<String, Object> data = execute("""
            { stores {
                edges { node { customersFirstN(first: 1) { firstName } } }
                nodes { customersFirstN(first: 1) { lastName } }
            } }
            """);

        var conn = (Map<String, Object>) data.get("stores");
        var edges = (List<Map<String, Object>>) conn.get("edges");
        var edgeCustomers = (List<Map<String, Object>>)
            ((Map<String, Object>) edges.get(0).get("node")).get("customersFirstN");
        assertThat(edgeCustomers).extracting(c -> c.get("firstName")).containsExactly("Mary");

        var nodes = (List<Map<String, Object>>) conn.get("nodes");
        var nodeCustomers = (List<Map<String, Object>>) nodes.get(0).get("customersFirstN");
        assertThat(nodeCustomers).extracting(c -> c.get("lastName")).containsExactly("Smith");
    }

    @Test
    void nameDivergence_onNonArgConsumingArm_failsLoudAsFieldError() {
        // Two distinct NestingFields (summary / info — arms that never read the SelectedField)
        // aliased to one result key across the sibling paths. The name check is universal (it
        // runs per bucket before the switch dispatch, not only in arg-consuming arms): dispatching
        // on the first occurrence's name would silently run summary's arm over info's
        // sub-selection and drop the diverging side.
        var result = executeRaw("""
            { filmsFaceted {
                edges { node { x: summary { title } } }
                nodes { x: info { releaseYear } }
            } }
            """);

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .contains("'x'")
            .contains("summary")
            .contains("info");
    }

    // ===== Non-connection control =====

    @Test
    @SuppressWarnings("unchecked")
    void nonConnectionQuery_singleOccurrencePath_behaviourUnchanged() {
        // Plain (non-connection) queries produce single-occurrence buckets everywhere; the
        // restructured shared path must behave exactly as before.
        Map<String, Object> data = execute("""
            { customers { firstName address { district } } }
            """);

        var customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers).hasSize(5)
            .allSatisfy(c -> {
                assertThat(c.get("firstName")).isNotNull();
                assertThat(((Map<String, Object>) c.get("address")).get("district")).isNotNull();
            });
    }
}
