package no.sikt.graphitron.example.server.match;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Base class for tests that run queries against the GraphQL server.
 * Runs given queries found under resources/match/queries
 * and returns ValidatableResponse that can be used for further assertions.
 */
public abstract class MatchTestBase {
    private static final Path QUERY_FILE_DIRECTORY = Paths.get("src", "test", "resources", "match", "queries");

    protected static ValidatableResponse getValidatableResponse(String fileName) {
        String query;
        try {
            query = Files.readString(QUERY_FILE_DIRECTORY.resolve(fileName));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", query))
                .when()
                .post("/graphql")
                .then()
                .statusCode(200);
    }
}