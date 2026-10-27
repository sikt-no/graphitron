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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyset paging over an order on a nullable column. Three of the eight seeded
 * {@code validity_period} rows have a {@code NULL} {@code valid_to}; graphitron sorts them last in
 * both directions and appends {@code period_id}, so at two rows a page a boundary falls inside the
 * {@code NULL} run and the next page seeks from a cursor whose sort key is {@code NULL}. Every walk
 * below returns each row exactly once with the {@code NULL} rows at the end: forward at the default
 * page size and backward with {@code last: 2}, through {@code @defaultOrder} in both directions and
 * through {@code @orderBy} under both runtime directions. The backward walks are what pin the
 * backward flip mirroring the null placement: without it the backward page's {@code NULL} rows land
 * on the wrong side of the cursor.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class NullSortKeyPagingExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    /** {@code valid_to ASC NULLS LAST, period_id ASC}: January, March, June, then the open periods. */
    private static final List<Integer> VALID_TO_ASC = List.of(3, 6, 1, 4, 8, 2, 5, 7);

    /** {@code valid_to DESC NULLS LAST, period_id DESC}: June, March, January, then the open periods. */
    private static final List<Integer> VALID_TO_DESC = List.of(8, 4, 1, 6, 3, 7, 5, 2);

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void defaultOrder_ascending_pagesThroughTheNullRunAtTheEnd() {
        Function<String, String> query = page -> "{ validityPeriodsByValidTo" + page + " " + SELECTION + " }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("validityPeriodsByValidTo");
        assertThat(forward(query, connection)).containsExactlyElementsOf(VALID_TO_ASC);
        assertThat(backward(query, connection)).containsExactlyElementsOf(VALID_TO_ASC);
    }

    @Test
    void defaultOrder_descending_keepsTheNullRowsLast() {
        Function<String, String> query = page -> "{ validityPeriodsByValidToDesc" + page + " " + SELECTION + " }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("validityPeriodsByValidToDesc");
        assertThat(forward(query, connection)).containsExactlyElementsOf(VALID_TO_DESC);
        assertThat(backward(query, connection)).containsExactlyElementsOf(VALID_TO_DESC);
    }

    @Test
    void orderByArgument_ascending_pagesThroughTheNullRunAtTheEnd() {
        Function<String, String> query = page -> "{ validityPeriodsOrdered" + withOrder(page, "ASC") + " " + SELECTION + " }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("validityPeriodsOrdered");
        assertThat(forward(query, connection)).containsExactlyElementsOf(VALID_TO_ASC);
        assertThat(backward(query, connection)).containsExactlyElementsOf(VALID_TO_ASC);
    }

    @Test
    void orderByArgument_runtimeDescending_keepsTheNullRowsLast() {
        Function<String, String> query = page -> "{ validityPeriodsOrdered" + withOrder(page, "DESC") + " " + SELECTION + " }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("validityPeriodsOrdered");
        assertThat(forward(query, connection)).containsExactlyElementsOf(VALID_TO_DESC);
        assertThat(backward(query, connection)).containsExactlyElementsOf(VALID_TO_DESC);
    }

    @Test
    void plainList_descending_putsTheNullRowsLast() {
        // A plain list gets no period_id tiebreaker, so rows that tie on valid_to come in any order;
        // the sort values are what is fixed.
        var rows = (List<Map<String, Object>>) execute("{ validityPeriodsByValidToDescList { validTo } }")
            .get("validityPeriodsByValidToDescList");
        assertThat(rows.stream().map(row -> row.get("validTo")).toList())
            .map(value -> value == null ? null : value.toString())
            .containsExactly("2026-06-01", "2026-03-01", "2026-03-01", "2026-01-01", "2026-01-01", null, null, null);
    }

    private static final String SELECTION =
        "{ nodes { periodId } pageInfo { startCursor endCursor hasNextPage hasPreviousPage } }";

    /** The page arguments with an {@code order:} argument in front. */
    private static String withOrder(String page, String direction) {
        var order = "order: {field: VALID_TO, direction: " + direction + "}";
        return page.isEmpty() ? "(" + order + ")" : "(" + order + ", " + page.substring(1);
    }

    /**
     * Every row of a walk from the start at the field's default page size, in the order the pages
     * returned them.
     */
    private static List<Integer> forward(Function<String, String> query,
                                         Function<Map<String, Object>, Map<String, Object>> connection) {
        var ids = new ArrayList<Integer>();
        String after = null;
        Map<String, Object> pageInfo;
        int pages = 0;
        do {
            var conn = connection.apply(execute(query.apply(after == null ? "" : "(after: \"" + after + "\")")));
            ids.addAll(periodIds(conn));
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
                "(last: 2" + (before == null ? "" : ", before: \"" + before + "\"") + ")")));
            ids.addAll(0, periodIds(conn));
            pageInfo = (Map<String, Object>) conn.get("pageInfo");
            before = (String) pageInfo.get("startCursor");
        } while (Boolean.TRUE.equals(pageInfo.get("hasPreviousPage")) && ++pages < 100);
        return ids;
    }

    private static List<Integer> periodIds(Map<String, Object> connection) {
        return ((List<Map<String, Object>>) connection.get("nodes")).stream()
            .map(node -> (Integer) node.get("periodId"))
            .toList();
    }

    private static Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as(query).isEmpty();
        return result.getData();
    }
}
