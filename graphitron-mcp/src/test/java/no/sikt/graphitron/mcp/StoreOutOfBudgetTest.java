package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.ReadBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this server does when a store read runs out of its budget: it fails the call.
 *
 * <p>Not the language server's posture, and it does not follow from it. An editor has prior state to
 * keep and a screen to leave standing, so degrading to "nothing new" costs the developer nothing
 * they were not already looking at. A turn-based server has neither. What it has instead is a caller
 * that reasons about the result, and the two failures below are exactly what that caller would
 * conclude from an empty one: that the graph declares no types, and that the catalog does not hold
 * the table it asked about. Both are wrong, and both would send an agent off to act on them.
 *
 * <p>The overrun is provoked by making a relation the production query reads non-terminating, so
 * each case turns on the response rather than on a clock. The timeouts are hang guards against that
 * shape escaping its budget, not assertions about it.
 */
class StoreOutOfBudgetTest {

    /** Asserted against never; the response has to name it, which is the only claim made about it. */
    private static final ReadBudget BOUNDED = new ReadBudget.Bounded(500);

    @TempDir
    Path tmp;

    /**
     * An empty type page reads as a graph that declares no types, so the {@code schema} tool errors
     * instead of paging one.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theSchemaToolFailsRatherThanPagingNoTypes() {
        try (var fixture = StoreFixture.ofSchema(tmp, "type Query { films: Int }\n");
             var reader = fixture.reader(BOUNDED)) {
            fixture.makeRunaway("graphql_type");

            var result = SchemaView.schemaResult(fixture.handle(), reader, Map.of());

            assertThat(result.isError())
                .as("an error, never a page with no types on it")
                .isTrue();
            assertThat(text(result))
                .contains("500 ms")
                .as("the statement that overran, which is what a bug report needs")
                .containsIgnoringCase("graphql_type");
        }
    }

    /**
     * The sharper hazard: an agent told a table is not in the catalog stops looking for it, and a
     * read that never finished said nothing about whether it is there.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theCatalogDescribeToolFailsRatherThanReportingNotFound() {
        try (var fixture = StoreFixture.ofCatalog(tmp);
             var reader = fixture.reader(BOUNDED)) {
            fixture.makeRunaway("sql_table");

            var result = GraphitronMcpServer.catalogDescribeResult(
                fixture.handle(), reader, Map.of("table", "public.film"));

            assertThat(result.isError())
                .as("an error, never the notFound arm")
                .isTrue();
            assertThat(text(result)).doesNotContain("was not found in the catalog");
            assertThat(text(result))
                .contains("500 ms")
                .as("the statement that overran, which is what a bug report needs")
                .containsIgnoringCase("sql_table");
        }
    }

    /** The rendered text of a tool result, which is what a caller reads first. */
    private static String text(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
        return result.content().stream()
            .map(Object::toString)
            .reduce("", (a, b) -> a + b);
    }
}
