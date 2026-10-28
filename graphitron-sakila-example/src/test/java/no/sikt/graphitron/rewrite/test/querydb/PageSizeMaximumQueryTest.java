package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.GraphQL;
import no.sikt.graphitron.generated.pagecap.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof of {@code <maxAllowedPageSize>}: the {@code rewrite-generate-page-cap}
 * execution generates {@code pagecap.graphqls} with a maximum of 2, and every connection kind it
 * declares serves a larger request at the maximum, with the page flags saying there is more.
 *
 * <p>The fixture rows are few, which is why the maximum is 2: five films, two staff, three customers
 * in store 1 and two in store 2. Every connection here has more rows than the maximum, and store 2
 * has exactly the maximum, so one parent of the split connection is clamped and the other is not.
 */
@ExecutionTier
class PageSizeMaximumQueryTest {

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();


        graphql = Graphitron.newGraphQL().build();
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @Test
    void aFirstAboveTheMaximumIsServedAtTheMaximum() {
        var page = connection(execute(
            "{ films(first: 3) { nodes { filmId } pageInfo { hasNextPage } } }"), "films");

        assertThat(filmIds(page)).containsExactly(1, 2);
        assertThat(pageInfo(page)).containsEntry("hasNextPage", true);
    }

    @Test
    void aFirstWithinTheMaximumIsServedAsAsked() {
        var page = connection(execute(
            "{ films(first: 1) { nodes { filmId } pageInfo { hasNextPage } } }"), "films");

        assertThat(filmIds(page)).containsExactly(1);
    }

    @Test
    void aLastAboveTheMaximumIsServedAtTheMaximumFromTheEnd() {
        // The before cursor comes off two forward pages, so it is cursor(4) whatever films other
        // classes have added after the seeded ones; asking for the end of the table would address
        // their rows.
        var first = connection(execute(
            "{ filmsConnection(first: 2) { pageInfo { endCursor } } }"), "filmsConnection");
        var second = connection(execute("{ filmsConnection(first: 2, after: \""
            + pageInfo(first).get("endCursor") + "\") { pageInfo { endCursor } } }"), "filmsConnection");
        var page = connection(execute("{ filmsConnection(last: 3, before: \""
            + pageInfo(second).get("endCursor") + "\") { nodes { filmId } pageInfo { hasPreviousPage } } }"),
            "filmsConnection");

        assertThat(filmIds(page)).containsExactly(2, 3);
        assertThat(pageInfo(page)).containsEntry("hasPreviousPage", true);
    }

    /**
     * Neither argument: the {@code @asConnection} field publishes the maximum as its default, and
     * the hand-declared connection, whose {@code first} has none, gets the built-in default lowered
     * to the maximum.
     */
    @Test
    void neitherArgumentServesTheMaximum() {
        var data = execute("""
            { films { nodes { filmId } } filmsConnection { nodes { filmId } } }
            """);

        assertThat(filmIds(connection(data, "films"))).hasSize(2);
        assertThat(filmIds(connection(data, "filmsConnection"))).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAsConnectionFirstDefaultIsTheMaximum() {
        var data = execute("""
            { __type(name: "Query") { fields { name args { name defaultValue } } } }
            """);

        var fields = (List<Map<String, Object>>) ((Map<String, Object>) data.get("__type")).get("fields");
        var films = fields.stream().filter(f -> f.get("name").equals("films")).findFirst().orElseThrow();
        assertThat((List<Map<String, Object>>) films.get("args"))
            .filteredOn(a -> a.get("name").equals("first"))
            .singleElement()
            .satisfies(a -> assertThat(a.get("defaultValue")).isEqualTo("2"));
    }

    /** The overflow guard is not reached: the maximum lowers the size before it. */
    @Test
    void theLargestIntIsAFullPageRatherThanAnError() {
        var page = connection(execute(
            "{ films(first: 2147483647) { nodes { filmId } } }"), "films");

        assertThat(filmIds(page)).containsExactly(1, 2);
    }

    /** A client asking for more than the maximum on every request still reaches every row once. */
    @Test
    @SuppressWarnings("unchecked")
    void followingTheCursorVisitsEveryRowOnce() {
        var seen = new ArrayList<Integer>();
        String after = null;
        for (int request = 0; request < 200; request++) {
            var arguments = after == null ? "first: 100" : "first: 100, after: \"" + after + "\"";
            var page = connection(execute("{ films(" + arguments + ")"
                + " { nodes { filmId } pageInfo { hasNextPage endCursor } } }"), "films");
            assertThat(filmIds(page)).as("every page is served at the maximum").hasSizeLessThanOrEqualTo(2);
            seen.addAll(filmIds(page));
            if (!(Boolean) pageInfo(page).get("hasNextPage")) {
                break;
            }
            after = (String) pageInfo(page).get("endCursor");
        }

        // Other classes add films above the seeded ones, so the walk is checked over the seeded
        // rows and for order and repetition across everything it visited.
        assertThat(seen).as("no row is repeated and the walk stays in key order")
            .doesNotHaveDuplicates().isSorted();
        assertThat(seen.stream().filter(id -> id <= 5).toList()).containsExactly(1, 2, 3, 4, 5);
    }

    /**
     * Per parent: store 1 has three customers and is served two with a next page, store 2 has
     * exactly two and is served both with none.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theSplitConnectionIsClampedPerParent() {
        var data = execute("""
            { stores { storeId customers(first: 5) { nodes { customerId } pageInfo { hasNextPage } } } }
            """);

        var stores = (List<Map<String, Object>>) data.get("stores");
        assertThat(stores).hasSize(2);
        for (var store : stores) {
            int storeId = (Integer) store.get("storeId");
            int total = dsl.fetchCount(DSL.table("customer"), DSL.field("store_id").eq(storeId));
            var customers = (Map<String, Object>) store.get("customers");
            assertThat((List<?>) customers.get("nodes"))
                .as("store %d holds %d customers", storeId, total)
                .hasSize(Math.min(total, 2));
            assertThat(pageInfo(customers))
                .as("store %d holds %d customers", storeId, total)
                .containsEntry("hasNextPage", total > 2);
        }
        assertThat(stores)
            .as("the fixture has a parent above the maximum and one at it")
            .anySatisfy(s -> assertThat(pageInfo((Map<String, Object>) s.get("customers")))
                .containsEntry("hasNextPage", true))
            .anySatisfy(s -> assertThat(pageInfo((Map<String, Object>) s.get("customers")))
                .containsEntry("hasNextPage", false));
    }

    @Test
    void thePolymorphicConnectionIsServedAtTheMaximum() {
        var page = connection(execute("""
            { occupants(first: 5) {
                nodes { __typename }
                pageInfo { hasNextPage }
            } }
            """), "occupants");

        assertThat((List<?>) page.get("nodes")).hasSize(2);
        assertThat(pageInfo(page)).containsEntry("hasNextPage", true);
    }

    // ===== Helpers =====

    @SuppressWarnings("unchecked")
    private static Map<String, Object> connection(Map<String, Object> data, String field) {
        return (Map<String, Object>) data.get(field);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pageInfo(Map<String, Object> connection) {
        return (Map<String, Object>) connection.get("pageInfo");
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> filmIds(Map<String, Object> connection) {
        return ((List<Map<String, Object>>) connection.get("nodes")).stream()
            .map(node -> (Integer) node.get("filmId"))
            .toList();
    }
}
