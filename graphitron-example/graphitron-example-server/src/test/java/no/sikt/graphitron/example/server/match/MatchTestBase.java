package no.sikt.graphitron.example.server.match;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Base class for tests that run queries against the GraphQL server.
 * Runs given queries found under resources/match/queries
 * and returns ValidatableResponse that can be used for further assertions.
 */
public abstract class MatchTestBase {
    protected abstract Path getFileDirectory();

    protected ValidatableResponse getValidatableResponse(String fileName) {
        return doPost(Map.of("query", readQueryFile(fileName)));
    }

    protected ValidatableResponse getValidatableResponse(String fileName, Map<String, Object> variables) {
        return doPost(Map.of("query", readQueryFile(fileName), "variables", variables));
    }

    private String readQueryFile(String fileName) {
        try {
            return Files.readString(getFileDirectory().resolve(fileName));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ValidatableResponse doPost(Map<String, Object> body) {
        return given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/graphql")
                .then()
                .statusCode(200);
    }
}