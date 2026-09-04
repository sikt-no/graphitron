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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof that a Relay connection projects the <em>union</em> of sub-selections when
 * the same result key is selected under both {@code edges { node { ... } }} and {@code nodes}.
 *
 * <p>graphql-java's {@code getFieldsGroupedByResultKey()} flattens the whole subtree, so the two
 * paths collapse into one bucket per result key. The generated {@code <Node>.$project} loop used to
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

    /**
     * The rows this class can name, from {@code init.sql}; every seeded film is at release year
     * 2006. Assertions here key off these lists rather than off a row count or a property
     * quantified over a whole table: on the local-db path the module's test classes share one
     * PostgreSQL instance, and sibling classes write to the tables these queries read, so a count
     * or a universal claim can fail on rows this class never wrote. See the concurrency notes in
     * {@code src/test/resources/junit-platform.properties}.
     */
    private static final List<String> SEED_FILM_TITLES = List.of(
        "ACADEMY DINOSAUR", "ACE GOLDFINGER", "ADAPTATION HOLES", "AFFAIR PREJUDICE", "AGENT TRUMAN");

    /** Store 1 owns Mary, Patricia and Barbara; store 2 owns Linda and Elizabeth. */
    private static final List<String> SEED_CUSTOMER_FIRST_NAMES = List.of(
        "Mary", "Patricia", "Linda", "Barbara", "Elizabeth");

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
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private graphql.ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
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

        // Both paths are located by storeId rather than by list position, and neither side asserts
        // how many stores came back: an index and a count are both claims about what `store`
        // holds, and this helper only ever means store 1. Nothing in the module writes `store`
        // today, so this is fixing a premise before it bites rather than a live failure.
        var conn = (Map<String, Object>) data.get("stores");
        var edgeStore1 = storeById((List<Map<String, Object>>) conn.get("edges"), "node", 1);
        var nodeStore1 = storeById((List<Map<String, Object>>) conn.get("nodes"), null, 1);

        // Store 1's own customer list stays an exact, ordered assertion. It is scoped to one named
        // store's children rather than to a table, and nothing in the module writes `customer`.
        var edgeCustomers = (List<Map<String, Object>>) edgeStore1.get("customers");
        assertThat(edgeCustomers).extracting(c -> c.get(expectedEdgeField))
            .containsExactly(expectedEdgeField.equals("firstName")
                ? new Object[] {"Mary", "Patricia", "Barbara"}
                : new Object[] {"Smith", "Johnson", "Jones"});

        var nodeCustomers = (List<Map<String, Object>>) nodeStore1.get("customers");
        assertThat(nodeCustomers).extracting(c -> c.get(expectedNodeField))
            .containsExactly(expectedNodeField.equals("firstName")
                ? new Object[] {"Mary", "Patricia", "Barbara"}
                : new Object[] {"Smith", "Johnson", "Jones"});
    }

    /**
     * Picks the store carrying {@code storeId} out of one connection path, unwrapping {@code node}
     * first when {@code nodeKey} is given, so neither path depends on how many stores the table
     * holds or on where in the page store 1 lands.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> storeById(List<Map<String, Object>> path, String nodeKey, int storeId) {
        return path.stream()
            .map(row -> nodeKey == null ? row : (Map<String, Object>) row.get(nodeKey))
            .filter(store -> Integer.valueOf(storeId).equals(store.get("storeId")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no store with storeId " + storeId + " in the page"));
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
        // storeId is selected on both paths so each side can locate store 1 by id rather than by
        // list position; `location` stays the only diverging bucket.
        Map<String, Object> data = execute("""
            { stores {
                edges { node { storeId location { address { district } } } }
                nodes { storeId location { address { address } } }
            } }
            """);

        var conn = (Map<String, Object>) data.get("stores");
        var edgeAddress = (Map<String, Object>) ((Map<String, Object>)
            storeById((List<Map<String, Object>>) conn.get("edges"), "node", 1).get("location")).get("address");
        assertThat(edgeAddress.get("district")).isEqualTo("Alberta");

        var nodeAddress = (Map<String, Object>) ((Map<String, Object>)
            storeById((List<Map<String, Object>>) conn.get("nodes"), null, 1).get("location")).get("address");
        assertThat(nodeAddress.get("address")).isEqualTo("47 MySakila Drive");
    }

    // ===== Polymorphic connection (multi-table Searchable union of Film + Actor) =====

    @Test
    @SuppressWarnings("unchecked")
    void polymorphicConnection_divergentNestedSelections_bothSidesResolve() {
        // The diverging bucket is Film's `summary` NestingField inside the restrictTo-filtered
        // selection: edges asks for summary.title, nodes for summary.releaseYear. The restrictTo
        // view preserves full occurrence lists per key, so one fix at the $project loop covers
        // this path too.
        //
        // Both paths also select the outer `title`, which is what keys the assertions below to the
        // films this case can name. Sibling classes in this module write `film` rows whose
        // release_year this case never chose, and on the local-db path they share one PostgreSQL
        // instance with it, so an assertion quantified over whatever `film` holds fails on rows
        // the case never wrote. Keyed by title, a stray row is simply never looked up. The outer
        // `title` is itself a two-occurrence bucket with identical selections, the shape
        // referenceUnderBothPaths_identicalSelections_behaviourUnchanged pins, so adding it leaves
        // the divergence under test on `summary` alone.
        Map<String, Object> data = execute("""
            { searchConnection(first: 100) {
                edges { node { __typename ... on Film { title summary { title } } } }
                nodes { __typename ... on Film { title summary { releaseYear } } }
            } }
            """);

        var conn = (Map<String, Object>) data.get("searchConnection");

        // summary.title remaps to the same FILM.TITLE column as the outer title, so the edges
        // side is pinned as an equality rather than a non-null check: a projection that reached
        // the right column for the wrong row would pass the weaker form.
        var edgeSummaryTitles = seedFilmSummaryLeaf(
            ((List<Map<String, Object>>) conn.get("edges")).stream()
                .map(e -> (Map<String, Object>) e.get("node"))
                .toList(),
            "title");
        assertThat(edgeSummaryTitles)
            .as("edges path: summary.title per seed film")
            .containsOnlyKeys(SEED_FILM_TITLES)
            .allSatisfy((outerTitle, summaryTitle) -> assertThat(summaryTitle).isEqualTo(outerTitle));

        var nodeReleaseYears = seedFilmSummaryLeaf(
            (List<Map<String, Object>>) conn.get("nodes"), "releaseYear");
        assertThat(nodeReleaseYears)
            .as("nodes path: summary.releaseYear per seed film")
            .containsOnlyKeys(SEED_FILM_TITLES)
            .allSatisfy((outerTitle, releaseYear) -> assertThat(releaseYear).isEqualTo(2006));
    }

    /**
     * Reads one leaf out of each seed film's {@code summary} bucket, keyed by the film's outer
     * {@code title} and dropping every film this case did not seed, so a sibling class's in-flight
     * {@code film} row cannot reach the assertion. Nulls are kept rather than dropped: a
     * regression that leaves the diverging leaf out of the SELECT must fail an assertion, not a
     * collector.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> seedFilmSummaryLeaf(List<Map<String, Object>> films, String leaf) {
        var bySeedTitle = new LinkedHashMap<String, Object>();
        films.stream()
            .filter(f -> "Film".equals(f.get("__typename")))
            .filter(f -> SEED_FILM_TITLES.contains(f.get("title")))
            .forEach(f -> {
                var summary = (Map<String, Object>) f.get("summary");
                bySeedTitle.put((String) f.get("title"), summary == null ? null : summary.get(leaf));
            });
        return bySeedTitle;
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
                edges { node { storeId customersFirstN(first: 1) { firstName } } }
                nodes { storeId customersFirstN(first: 1) { lastName } }
            } }
            """);

        var conn = (Map<String, Object>) data.get("stores");
        var edgeCustomers = (List<Map<String, Object>>)
            storeById((List<Map<String, Object>>) conn.get("edges"), "node", 1).get("customersFirstN");
        assertThat(edgeCustomers).extracting(c -> c.get("firstName")).containsExactly("Mary");

        var nodeCustomers = (List<Map<String, Object>>)
            storeById((List<Map<String, Object>>) conn.get("nodes"), null, 1).get("customersFirstN");
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
        //
        // Keyed to the seed customers rather than asserted as a count of five. The count was a
        // claim about what `customer` holds, and what the case means is that each customer it
        // seeded projects both a firstName and a district through the single-occurrence path.
        Map<String, Object> data = execute("""
            { customers { firstName address { district } } }
            """);

        var districtsBySeedCustomer = new LinkedHashMap<String, Object>();
        ((List<Map<String, Object>>) data.get("customers")).stream()
            .filter(c -> SEED_CUSTOMER_FIRST_NAMES.contains(c.get("firstName")))
            .forEach(c -> {
                var address = (Map<String, Object>) c.get("address");
                districtsBySeedCustomer.put((String) c.get("firstName"),
                    address == null ? null : address.get("district"));
            });
        assertThat(districtsBySeedCustomer)
            .containsOnlyKeys(SEED_CUSTOMER_FIRST_NAMES)
            .allSatisfy((firstName, district) -> assertThat(district).isNotNull());
    }
}
