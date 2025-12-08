package no.sikt.graphitron.example.server.querytracking;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that COUNT queries for totalCount are only executed
 * when totalCount is actually requested in the GraphQL selection set.
 */
@QuarkusTest
@DisplayName("TotalCount conditional execution")
public class TotalCountConditionalExecutionTest extends QueryTrackingTestBase {

    @Test
    @DisplayName("COUNT query should NOT be executed when totalCount is not in selection set")
    void countQueryNotExecutedWhenTotalCountNotSelected() {
        executeQuery("""
            {
                customers(first: 2) {
                    nodes { id }
                }
            }
            """);

        assertThat(hasCountQuery())
                .as("COUNT query should NOT be executed")
                .isFalse();
    }

    @Test
    @DisplayName("COUNT query should be executed when totalCount is in selection set")
    void countQueryExecutedWhenTotalCountSelected() {
        executeQuery("""
            {
                customers(first: 2) {
                    totalCount
                    nodes { id }
                }
            }
            """);

        assertThat(countCountQueries())
                .as("Exactly one COUNT query should be executed")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Nested: COUNT query should NOT be executed when totalCount is not selected")
    void nestedCountQueryNotExecutedWhenTotalCountNotSelected() {
        executeQuery("""
            {
                cities(first: 2) {
                    nodes {
                        id
                        addressesPaginated(first: 3) {
                            nodes { id }
                        }
                    }
                }
            }
            """);

        assertThat(hasCountQuery())
                .as("COUNT query should NOT be executed for nested queries")
                .isFalse();
    }

    @Test
    @DisplayName("Nested: COUNT query should be executed when totalCount is selected")
    void nestedCountQueryExecutedWhenTotalCountSelected() {
        executeQuery("""
            {
                cities(first: 2) {
                    nodes {
                        id
                        addressesPaginated(first: 3) {
                            totalCount
                            nodes { id }
                        }
                    }
                }
            }
            """);

        assertThat(hasCountQuery())
                .as("COUNT query should be executed for nested field with totalCount")
                .isTrue();
    }
}
