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
 * Execution-tier coverage for a node id over a converter-backed temporal key column: each id
 * reaches the row it was minted from.
 *
 * <p>The key is {@code converter_id_change (person_id, changed_at)}, where {@code changed_at} is a
 * {@code timestamptz} domain whose jOOQ codegen attached
 * {@link no.sikt.graphitron.rewrite.fixtures.codegen.UtcLocalDateTimeConverter}, so generated code
 * handles it as {@code LocalDateTime}. The encoder writes that value's {@code toString()}, which the
 * column's database type does not parse: decoded through the converted data type, the slot names no
 * row here, and on an Oracle {@code DATE} mapped the same way it names the midnight row of the same
 * day. Both seeded rows belong to one person on one day, at 00:00 and 02:24, so a decode that lost
 * the time could only answer the other row.
 *
 * <p>The ids are read off the {@code converterIdChanges} listing rather than minted in the test, so
 * each is exactly what the server wrote.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ConverterTemporalKeyExecutionTest {

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

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    @Test
    void node_eachIdAnswersItsOwnRow() {
        String midnight = idOf("midnight");
        String smallHours = idOf("small hours");
        assertThat(midnight).isNotEqualTo(smallHours);
        for (var entry : Map.of(midnight, "midnight", smallHours, "small hours").entrySet()) {
            var node = (Map<String, Object>) execute("{ node(id: \"" + entry.getKey() + "\")"
                + " { id ... on ConverterIdChange { newValue } } }").get("node");
            assertThat(node).as("node for the %s row", entry.getValue())
                .containsEntry("newValue", entry.getValue())
                .containsEntry("id", entry.getKey());
        }
    }

    @Test
    void nodes_bothIdsAnswerBothRowsInOrder() {
        String midnight = idOf("midnight");
        String smallHours = idOf("small hours");
        var nodes = (List<Map<String, Object>>) execute("{ nodes(ids: [\"" + smallHours + "\", \""
            + midnight + "\"]) { ... on ConverterIdChange { newValue } } }").get("nodes");
        assertThat(nodes).extracting(row -> row == null ? null : row.get("newValue"))
            .containsExactly("small hours", "midnight");
    }

    @Test
    void nodeIdFilter_selectsTheSmallHoursRowOnly() {
        String smallHours = idOf("small hours");
        var rows = (List<Map<String, Object>>) execute("{ converterIdChangeByNodeId(filter: {id: \""
            + smallHours + "\"}) { newValue } }").get("converterIdChangeByNodeId");
        assertThat(rows).extracting(row -> row.get("newValue")).containsExactly("small hours");
    }

    /** The node id the server minted for the row with the given value, read off the listing root. */
    private String idOf(String newValue) {
        var rows = (List<Map<String, Object>>) execute("{ converterIdChanges { id newValue } }")
            .get("converterIdChanges");
        return rows.stream()
            .filter(row -> newValue.equals(row.get("newValue")))
            .map(row -> (String) row.get("id"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no converter_id_change row with value " + newValue));
    }
}
