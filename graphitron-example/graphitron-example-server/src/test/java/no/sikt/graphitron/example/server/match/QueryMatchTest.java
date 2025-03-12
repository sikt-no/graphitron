package no.sikt.graphitron.example.server.match;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@DisplayName("Test basic retrieval of data from the GraphQL API")
public class QueryMatchTest extends MatchTestBase {

    @Test
    @DisplayName("Test retrieval of data on nested types")
    public void nested() {
        var queryFile = "query_basic_nested.graphql";

        getValidatableResponse(queryFile)
                .rootPath("data.customers.nodes[0]")
                .body("id", is(notNullValue()))
                .body("name.lastName", is(notNullValue()))
                .body("address.addressLine1", is(notNullValue()))
                .body("address.city.name", is(notNullValue()));
    }
}