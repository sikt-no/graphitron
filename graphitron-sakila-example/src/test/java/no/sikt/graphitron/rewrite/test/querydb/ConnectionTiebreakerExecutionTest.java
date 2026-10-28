package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyset paging over a connection whose order does not identify a row. Three of the five seeded
 * films tie at {@code rental_rate} 2.99, so a two-row page ends inside the tie, and a seek on
 * {@code rental_rate} alone would skip the rest of it. Graphitron appends {@code film_id} to such
 * an order, its cursor and its seek, so every walk below returns each seeded film exactly once:
 * forward and backward, through {@code @defaultOrder}, through a single-valued and a list-valued
 * {@code @orderBy} in both runtime directions (the appended key flips with the rate), and through the
 * {@code @splitQuery} child connection the issue was reported against.
 *
 * <p>Other classes insert films into the database this one shares, while this one runs, so a walk
 * is judged on the seeded rows: they arrive exactly once each and in the expected order, and no row
 * arrives twice. Comparing the walked count against {@code totalCount} would compare two reads
 * another class can write between.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ConnectionTiebreakerExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    /** Seeded films ordered by {@code rental_rate, film_id} ascending: 0.99, the 2.99 tie, 4.99. */
    private static final List<Integer> RATE_ASC = List.of(1, 3, 4, 5, 2);

    /** The same order descending, the key flipped with the rate. */
    private static final List<Integer> RATE_DESC = List.of(2, 5, 4, 3, 1);

    private static final List<Integer> SEEDED_FILM_IDS = List.of(1, 2, 3, 4, 5);

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        graphql = Graphitron.newGraphQL().build();
    }


    @Test
    void defaultOrder_forwardWalk_returnsEveryTiedRowOnce() {
        assertWalk(forward(page -> "{ filmsByRentalRate(" + page + ") " + SELECTION + " }",
            data -> (Map<String, Object>) data.get("filmsByRentalRate")), RATE_ASC);
    }

    @Test
    void defaultOrder_backwardWalk_returnsEveryTiedRowOnce() {
        assertWalk(backward(page -> "{ filmsByRentalRate(" + page + ") " + SELECTION + " }",
            data -> (Map<String, Object>) data.get("filmsByRentalRate")), RATE_ASC);
    }

    @Test
    void listOrderByArgument_ascending_forwardAndBackward_returnEveryTiedRowOnce() {
        Function<String, String> query = page -> "{ filmsOrderedConnection(order: [{field: RENTAL_RATE, direction: ASC}], "
            + page + ") " + SELECTION + " }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("filmsOrderedConnection");
        assertWalk(forward(query, connection), RATE_ASC);
        assertWalk(backward(query, connection), RATE_ASC);
    }

    @Test
    void listOrderByArgument_descending_flipsTheAppendedKey() {
        Function<String, String> query = page -> "{ filmsOrderedConnection(order: [{field: RENTAL_RATE, direction: DESC}], "
            + page + ") " + SELECTION + " }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("filmsOrderedConnection");
        assertWalk(forward(query, connection), RATE_DESC);
        assertWalk(backward(query, connection), RATE_DESC);
    }

    @Test
    void singleOrderByArgument_ascending_forwardAndBackward_returnEveryTiedRowOnce() {
        Function<String, String> query = page -> "{ filmsOrderedByOneKeyConnection(order: {field: RENTAL_RATE, direction: ASC}, "
            + page + ") " + SELECTION + " }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("filmsOrderedByOneKeyConnection");
        assertWalk(forward(query, connection), RATE_ASC);
        assertWalk(backward(query, connection), RATE_ASC);
    }

    @Test
    void singleOrderByArgument_descending_flipsTheAppendedKey() {
        Function<String, String> query = page -> "{ filmsOrderedByOneKeyConnection(order: {field: RENTAL_RATE, direction: DESC}, "
            + page + ") " + SELECTION + " }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("filmsOrderedByOneKeyConnection");
        assertWalk(forward(query, connection), RATE_DESC);
        assertWalk(backward(query, connection), RATE_DESC);
    }

    @Test
    void splitQueryChildConnection_forwardAndBackward_returnEveryTiedRowOnce() {
        // Language 1 holds every seeded film, so its partition holds the 2.99 tie.
        Function<String, String> query = page -> "{ languageBySingleKey(language_id: 1) { filmsByRentalRate("
            + page + ") " + SELECTION + " } }";
        Function<Map<String, Object>, Map<String, Object>> connection = data ->
            (Map<String, Object>) ((Map<String, Object>) data.get("languageBySingleKey")).get("filmsByRentalRate");
        assertWalk(forward(query, connection), RATE_ASC);
        assertWalk(backward(query, connection), RATE_ASC);
    }

    private static final String SELECTION =
        "{ nodes { filmId } pageInfo { startCursor endCursor hasNextPage hasPreviousPage } }";

    /** Every row of a {@code first: 2} walk from the start, in the order the pages returned them. */
    private static List<Integer> forward(Function<String, String> query,
                                         Function<Map<String, Object>, Map<String, Object>> connection) {
        var ids = new ArrayList<Integer>();
        String after = null;
        Map<String, Object> pageInfo;
        int pages = 0;
        do {
            var conn = connection.apply(execute(query.apply(
                "first: 2" + (after == null ? "" : ", after: \"" + after + "\""))));
            ids.addAll(filmIds(conn));
            pageInfo = (Map<String, Object>) conn.get("pageInfo");
            after = (String) pageInfo.get("endCursor");
        } while (Boolean.TRUE.equals(pageInfo.get("hasNextPage")) && ++pages < 100);
        return ids;
    }

    /** Every row of a {@code last: 2} walk from the end, put back in forward order. */
    private static List<Integer> backward(Function<String, String> query,
                                          Function<Map<String, Object>, Map<String, Object>> connection) {
        var ids = new ArrayList<Integer>();
        String before = null;
        Map<String, Object> pageInfo;
        int pages = 0;
        do {
            var conn = connection.apply(execute(query.apply(
                "last: 2" + (before == null ? "" : ", before: \"" + before + "\""))));
            ids.addAll(0, filmIds(conn));
            pageInfo = (Map<String, Object>) conn.get("pageInfo");
            before = (String) pageInfo.get("startCursor");
        } while (Boolean.TRUE.equals(pageInfo.get("hasPreviousPage")) && ++pages < 100);
        return ids;
    }

    private static void assertWalk(List<Integer> walked, List<Integer> expectedSeededOrder) {
        assertThat(walked)
            .as("no row arrives twice across the page boundaries")
            .doesNotHaveDuplicates();
        assertThat(walked.stream().filter(SEEDED_FILM_IDS::contains).toList())
            .as("every seeded film arrives exactly once, the 2.99 tie included, in rate-then-id order")
            .containsExactlyElementsOf(expectedSeededOrder);
    }

    private static List<Integer> filmIds(Map<String, Object> connection) {
        return ((List<Map<String, Object>>) connection.get("nodes")).stream()
            .map(node -> (Integer) node.get("filmId"))
            .toList();
    }

    private static Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as(query).isEmpty();
        return result.getData();
    }
}
