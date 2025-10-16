package no.sikt.graphitron.example.server.match;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@DisplayName("Test basic retrieval of data from the GraphQL API")
public class QueryMatchTest extends MatchTestBase {

    @Override
    protected Path getFileDirectory() {
        return Paths.get("src", "test", "resources", "match", "queries");
    }

    @Test
    @DisplayName("Data on nested types can be retrieved")
    public void nested() {
        var queryFile = "query_basic_nested.graphql";

        getValidatableResponse(queryFile)
                .rootPath("data.customers.nodes[0]")
                .body("id", is(notNullValue()))
                .body("name.lastName", is(notNullValue()))
                .body("address.addressLine1", is(notNullValue()))
                .body("address.city.name", is(notNullValue()));
    }

    @Test
    @DisplayName("The federation _service query works")
    public void federationService() {
        var queryFile = "query_federation_service.graphql";

        getValidatableResponse(queryFile)
                .rootPath("data._service.sdl")
                .body("length()", is(notNullValue()));
    }
}