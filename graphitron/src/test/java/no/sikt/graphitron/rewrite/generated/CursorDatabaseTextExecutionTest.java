package no.sikt.graphitron.rewrite.generated;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.facts.cursor_database_text.Graphitron;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Connections of the fact scenario {@code cursor-database-text.graphqls}, each ordered on a column
 * whose value Java does not carry exactly, walked one row a page from either end through the
 * generated facade: forward with {@code first: 1} following {@code endCursor}, backward with
 * {@code last: 1} following {@code startCursor}. Every walk returns each row once, in the order the
 * database gives the unpaged query, which is the oracle each walk is compared against. The database
 * writes these columns' cursor elements and reads them back, so the next page starts after the row
 * its cursor was taken from: a range ending in {@code infinity} is not read back as one with no
 * upper bound, a {@code bytea} key not as the bytes of its array's identity text, a {@code time}
 * not without its fraction of a second, an array not refused, and a {@code macaddr} or a
 * {@code daterange} jOOQ cannot read pages while nothing selects it.
 *
 * <p>The covered paths: a root connection, the batched child connection under a list of parents,
 * and a multi-table connection over a union of two {@code bytea}-keyed tables, at the root and
 * batched; a selection with no field of the order column, and one that selects it.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class CursorDatabaseTextExecutionTest {

    private static final GraphQL GRAPHQL = Graphitron.newGraphQL().build();

    private static final String PAGE_INFO = "pageInfo { startCursor endCursor hasNextPage hasPreviousPage }";

    private static final String BLOB_NODES = "nodes { ... on CursorBlobA { rowId } ... on CursorBlobB { rowId } }";

    // The issue's failure: a descending page on a range key whose rows end in infinity. A cursor
    // read back through Java names [x,) for the row holding [x,infinity), and on a descending page
    // the seek finds that row again, forever.
    @Test
    void aRangeKeyPagesDescending_pastTheRowEndingInInfinity() {
        var expected = oracle("select row_id from cursor_period order by subject_id desc, valid_period desc");
        assertWalks(root("periodsDesc", "nodes { rowId }"), expected);
    }

    @Test
    void aRangeKeyPagesAscending() {
        assertWalks(root("periods", "nodes { rowId }"),
            oracle("select row_id from cursor_period order by subject_id, valid_period"));
    }

    @Test
    void aRangeKeyPagesUnderEachParent_inTheBatch() {
        for (int subject : List.of(1, 2)) {
            assertWalks(child("periods", subject, "nodes { rowId }"),
                oracle("select row_id from cursor_period where subject_id = " + subject + " order by valid_period"));
            assertWalks(child("periodsDesc", subject, "nodes { rowId }"),
                oracle("select row_id from cursor_period where subject_id = " + subject + " order by valid_period desc"));
        }
    }

    @Test
    void aUnionOfByteaKeyedTablesPagesAtTheRoot() {
        assertWalks(root("blobs", BLOB_NODES), oracle("""
            select row_id from (
                select blob_key, 'CursorBlobA' as typename, row_id from cursor_blob_a
                union all select blob_key, 'CursorBlobB', row_id from cursor_blob_b) u
            order by blob_key, typename"""));
    }

    @Test
    void aUnionOfByteaKeyedTablesPagesUnderEachParent_inTheBatch() {
        for (int subject : List.of(1, 2)) {
            assertWalks(child("blobs", subject, BLOB_NODES), oracle("""
                select row_id from (
                    select blob_key, 'CursorBlobA' as typename, row_id, owner_id from cursor_blob_a
                    union all select blob_key, 'CursorBlobB', row_id, owner_id from cursor_blob_b) u
                where owner_id = %d order by blob_key, typename""".formatted(subject)));
        }
    }

    @Test
    void anArrayColumnPages_unselectedAndSelected() {
        for (var direction : List.of("ASC", "DESC")) {
            var expected = oracle("select row_id from cursor_sort_value order by numbers " + direction);
            assertWalks(ordered("sortValues", "NUMBERS", direction, "nodes { rowId }"), expected);
            assertWalks(ordered("sortValues", "NUMBERS", direction, "nodes { rowId numbers }"), expected);
        }
    }

    @Test
    void aTimeWithFractionsOfASecondPages() {
        for (var direction : List.of("ASC", "DESC")) {
            assertWalks(ordered("sortValues", "AT_TIME", direction, "nodes { rowId }"),
                oracle("select row_id from cursor_sort_value order by at_time " + direction));
        }
    }

    @Test
    void aMacaddrJooqTypesAsObjectPages() {
        for (var direction : List.of("ASC", "DESC")) {
            assertWalks(ordered("sortTexts", "MAC", direction, "nodes { rowId }"),
                oracle("select row_id from cursor_sort_text order by mac " + direction));
        }
    }

    // jOOQ cannot read a daterange with an infinity bound, so the page reads only its text.
    @Test
    void aDaterangeJooqCannotReadPages_whileNothingSelectsIt() {
        for (var direction : List.of("ASC", "DESC")) {
            assertWalks(ordered("sortTexts", "SPAN", direction, "nodes { rowId }"),
                oracle("select row_id from cursor_sort_text order by span " + direction));
        }
    }

    @Test
    void theSelectedArrayIsTheRowsOwn() {
        var connection = (Map<String, Object>) execute(
            "{ sortValues(order: {field: NUMBERS}, first: 2) { nodes { rowId numbers } } }").get("sortValues");
        assertThat((List<Map<String, Object>>) connection.get("nodes"))
            .extracting(node -> node.get("rowId"), node -> node.get("numbers"))
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(5, List.of()),
                org.assertj.core.groups.Tuple.tuple(1, List.of(1, 2)));
    }

    // Only the database can read a database-written element, so one edited into no value of its
    // column's type fails when the page runs: the redacted error, not a page and not a client error.
    @Test
    void anElementTheDatabaseCannotRead_failsWhenThePageRuns() {
        var result = run("{ periods(first: 1, after: \"" + cursor("[1,\"garbage\"]") + "\") { nodes { rowId } } }");
        assertThat(result.getErrors()).singleElement().satisfies(error ->
            assertThat(error.getMessage())
                .startsWith("An error occurred. Reference: ")
                .doesNotContain("garbage"));
    }

    @Test
    void aMalformedCursorStaysAClientError() {
        for (var after : List.of("!!!", cursor("[1]"), cursor("[1,true]"))) {
            var result = run("{ periods(first: 1, after: \"" + after + "\") { nodes { rowId } } }");
            assertThat(result.getErrors()).as(after).singleElement().satisfies(error ->
                assertThat(error.getMessage()).contains("cursor is not valid"));
        }
    }

    /** A walk over one connection: the query text for a page's arguments, and where its connection is. */
    private record Walk(Function<String, String> query, Function<Map<String, Object>, Map<String, Object>> connection) {}

    private static Walk root(String field, String nodes) {
        return new Walk(page -> "{ " + field + page + " { " + nodes + " " + PAGE_INFO + " } }",
            data -> (Map<String, Object>) data.get(field));
    }

    /** The connection under one subject, fetched with every subject in the batch. */
    private static Walk child(String field, int subject, String nodes) {
        return new Walk(page -> "{ subjects { subjectId " + field + page + " { " + nodes + " " + PAGE_INFO + " } } }",
            data -> ((List<Map<String, Object>>) data.get("subjects")).stream()
                .filter(s -> Integer.valueOf(subject).equals(s.get("subjectId")))
                .map(s -> (Map<String, Object>) s.get(field))
                .findFirst().orElseThrow());
    }

    private static Walk ordered(String field, String sort, String direction, String nodes) {
        var order = "order: {field: " + sort + ", direction: " + direction + "}, ";
        return new Walk(page -> "{ " + field + "(" + order + page.substring(1) + " { " + nodes + " " + PAGE_INFO + " } }",
            data -> (Map<String, Object>) data.get(field));
    }

    private static void assertWalks(Walk walk, List<Integer> expected) {
        assertThat(forward(walk)).as("forward").containsExactlyElementsOf(expected);
        assertThat(backward(walk)).as("backward").containsExactlyElementsOf(expected);
    }

    private static List<Integer> forward(Walk walk) {
        var ids = new ArrayList<Integer>();
        String after = null;
        Map<String, Object> pageInfo;
        int pages = 0;
        do {
            var conn = walk.connection().apply(execute(walk.query().apply(
                "(first: 1" + (after == null ? "" : ", after: \"" + after + "\"") + ")")));
            ids.addAll(rowIds(conn));
            pageInfo = (Map<String, Object>) conn.get("pageInfo");
            after = (String) pageInfo.get("endCursor");
        } while (Boolean.TRUE.equals(pageInfo.get("hasNextPage")) && ++pages < 50);
        return ids;
    }

    private static List<Integer> backward(Walk walk) {
        var ids = new ArrayList<Integer>();
        String before = null;
        Map<String, Object> pageInfo;
        int pages = 0;
        do {
            var conn = walk.connection().apply(execute(walk.query().apply(
                "(last: 1" + (before == null ? "" : ", before: \"" + before + "\"") + ")")));
            ids.addAll(0, rowIds(conn));
            pageInfo = (Map<String, Object>) conn.get("pageInfo");
            before = (String) pageInfo.get("startCursor");
        } while (Boolean.TRUE.equals(pageInfo.get("hasPreviousPage")) && ++pages < 50);
        return ids;
    }

    private static List<Integer> rowIds(Map<String, Object> connection) {
        return ((List<Map<String, Object>>) connection.get("nodes")).stream()
            .map(node -> (Integer) node.get("rowId"))
            .toList();
    }

    private static List<Integer> oracle(String sql) {
        return TestDatabase.dsl().fetch(sql).getValues(0, Integer.class);
    }

    private static String cursor(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ExecutionResult run(String query) {
        return GRAPHQL.execute(Graphitron.newExecutionInput(TestDatabase.dsl()).query(query).build());
    }

    private static Map<String, Object> execute(String query) {
        var result = run(query);
        assertThat(result.getErrors()).as(query).isEmpty();
        return result.getData();
    }
}
