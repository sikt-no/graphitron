package no.sikt.graphitron.example.server.querytracking;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@DisplayName("Optional select execution")
public class OptionalSelectExecutionTest extends QueryTrackingTestBase {

    @Test
    @DisplayName("Subquery references are NOT selected when the corresponding field is NOT requested")
    void subqueryReferenceIsSkippedWhenNotRequested() {
        executeQuery("""
                {
                  customerForSelectionSetTest(first: 1) {
                    nodes {
                      customerId
                    }
                  }
                }
            """);

        assertThat(getExecutedQueries()).as("One query should have been executed.")
                .hasSize(1);

        assertThat(getExecutedQueries().get(0)).as("Query without address field should use null placeholder")
                .contains(".\"customer_id\", null") // Null as placeholder for CustomerForSelectionSetTest.address
                .contains(".\"address_id\", null") // Make sure non-reference field (addressId) is not impacted, but reference field (cityId) has null placeholder
                .doesNotContain("\"public\".\"address\"") // Make sure query does not include correlated subquery for CustomerForSelectionSetTest.address
                .doesNotContain("\"public\".\"city\""); // Make sure query does not include correlated subquery for CustomerForSelectionSetTest.cityId
    }

    @Test
    @DisplayName("Subquery references are selected when the corresponding field is requested")
    void subqueryIsSelectedWhenRequested() {
        executeQuery("""
                {
                  customerForSelectionSetTest(first: 1) {
                    nodes {
                      customerId
                      address { id }
                      cityId
                    }
                  }
                }
            """);

        assertThat(getExecutedQueries()).as("One query should have been executed.")
                .hasSize(1);

        assertThat(getExecutedQueries().get(0)).as("Query should include correlated subquery")
                .contains("from \"public\".\"address\"")
                .contains("join \"public\".\"city\"")
                .doesNotContain(".\"customer_id\", null") // No null in place of subquery reference (address)
                .doesNotContain(".\"address_id\", null"); // No null in place of subquery reference (address)
    }

    @Test
    @DisplayName("Subquery references after a splitQuery field are NOT selected when NOT requested")
    void subqueryReferenceAfterSplitQueryFieldIsSkippedWhenNotRequested() {
        executeQuery("""
                    {
                      stores(first: 1) {
                        nodes {
                          customerForSelectionSetTest(first: 1) {
                            nodes {
                              customerId
                            }
                          }
                        }
                      }
                    }
                """);

        assertThat(getExecutedQueries()).as("One query should be executed for root field, and another for the splitQuery field.")
                .hasSize(2);

        assertThat(getExecutedQueries().get(1)).as("Query for 'customerForSelectionSetTest' should skip correlated subquery")
                .doesNotContain("from \"public\".\"address\"")
                .doesNotContain("join \"public\".\"city\"")
                .contains(".\"customer_id\", null")
                .contains(".\"address_id\", null");
    }

    @Test
    @DisplayName("Subquery references after a splitQuery field should be selected when requested")
    void subqueryReferenceAfterSplitQueryFieldIsSelectedWhenRequested() {
        executeQuery("""
                    {
                      stores(first: 1) {
                        nodes {
                          customerForSelectionSetTest(first: 1) {
                            nodes {
                              customerId
                              address {
                                id
                              }
                              cityId
                            }
                          }
                        }
                      }
                    }
                """);

        assertThat(getExecutedQueries()).as("One query should be executed for root field, and another for the splitQuery field.")
                .hasSize(2);

        assertThat(getExecutedQueries().get(1)).as("Query for 'customerForSelectionSetTest' should skip correlated subquery")
                .contains("from \"public\".\"address\"")
                .contains("join \"public\".\"city\"");
    }

    @Test
    @DisplayName("External fields are NOT selected when the corresponding field is NOT requested")
    void externalFieldIsSkippedWhenNotRequested() {
        executeQuery("""
                {
                  customerForSelectionSetTest(first: 1) {
                    nodes {
                      customerId
                    }
                  }
                }
            """);

        assertThat(getExecutedQueries()).as("One query should have been executed.")
                .hasSize(1);

        assertThat(getExecutedQueries().get(0)).as("Query without nameFormatted field should use null placeholder")
                .doesNotContain("first_name")
                .doesNotContain("last_name");
    }

    @Test
    @DisplayName("External fields are selected when the corresponding field is requested")
    void externalFieldIsSelectedWhenRequested() {
        executeQuery("""
                {
                  customerForSelectionSetTest(first: 1) {
                    nodes {
                      customerId
                      nameFormatted
                    }
                  }
                }
            """);

        assertThat(getExecutedQueries()).as("One query should have been executed.")
                .hasSize(1);

        assertThat(getExecutedQueries().get(0)).as("Query should include external field computation")
                .contains("first_name")
                .contains("last_name");
    }
}
