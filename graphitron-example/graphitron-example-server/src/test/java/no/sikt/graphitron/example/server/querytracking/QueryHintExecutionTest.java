package no.sikt.graphitron.example.server.querytracking;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that generated entry-point queries carry a SQL comment hint of the form
 * {@code DataFetcher=Type.field build=...} so they can be identified in database logs and query plans.
 */
@QuarkusTest
@DisplayName("Query hint emission")
public class QueryHintExecutionTest extends QueryTrackingTestBase {

    @Test
    @DisplayName("Root query carries a DataFetcher= hint identifying the operation field")
    void queryHasHint() {
        executeQuery("""
                {
                    customers(first: 1) {
                        nodes { id }
                    }
                }
                """);

        assertThat(getExecutedQueries()).hasSize(1);
        assertThat(getExecutedQueries().get(0))
                .as("Root query should be tagged with its DataFetcher path")
                .contains("/* DataFetcher=Query.customers build=graphitron-example-spec:");
    }
}