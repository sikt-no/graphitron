package no.sikt.graphitron.example.server.querytracking;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
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
    void rootQueryHasHint() {
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
                .contains("/* DataFetcher=Query.customers build=");
    }

    @Test
    @DisplayName("Split-query field carries its own DataFetcher= hint, distinct from the parent")
    void splitQueryHasHint() {
        executeQuery("""
                {
                    customers(first: 1) {
                        nodes {
                            addressSplitQuery {
                                id
                            }
                        }
                    }
                }
                """);

        assertThat(getExecutedQueries())
                .as("Two queries: parent stores and the split-query for customerForSelectionSetTest")
                .hasSize(2);

        assertThat(getExecutedQueries().get(1)).contains("DataFetcher=Customer.addressSplitQuery build=");
    }

    @Test
    @DisplayName("Count query carries a DataFetcher= hint for the field whose total it counts")
    void countQueryHasHint() {
        executeQuery("""
                {
                    customers(first: 1) {
                        totalCount
                    }
                }
                """);

        assertThat(getCountQueries().size()).isEqualTo(1);
        assertThat(getCountQueries().get(0)).contains("DataFetcher=Query.customers");
    }

    @Test
    @DisplayName("Multi-table union query carries a DataFetcher= hint at the root")
    void multiTableUnionHasHint() {
        executeQuery("""
                {
                    languageOrStaff(first: 1) {
                        nodes {
                            ... on Language { id }
                            ... on Staff { id }
                        }
                    }
                }
                """);

        assertThat(getExecutedQueries()).hasSize(1);
        assertThat(getExecutedQueries().get(0)).contains("DataFetcher=Query.languageOrStaff");
    }

    @Test
    @DisplayName("Federation _entities resolution carries a DataFetcher=_Entity.<type> hint")
    void federationEntityResolutionHasHint() {
        var query = """
                query FederationEntities($representations: [_Any!]!) {
                    _entities(representations: $representations) {
                        ... on FederatedStaff { id }
                    }
                }
                """;
        var representations = List.of(Map.of(
                "__typename", "FederatedStaff",
                "email", "Mike.Hillyer@sakilastaff.com",
                "username", "Mike"
        ));

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", query, "variables", Map.of("representations", representations)))
                .post("graphql")
                .then()
                .statusCode(200);

        assertThat(getExecutedQueries()).hasSize(1);
        assertThat(getExecutedQueries().get(0)).contains("DataFetcher=Query._entity query=FederatedStaffDBQueries.federatedStaffFor_Entity build=");
    }
}
