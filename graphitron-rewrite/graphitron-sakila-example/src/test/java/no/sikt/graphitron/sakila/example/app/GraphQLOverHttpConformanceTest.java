package no.sikt.graphitron.sakila.example.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Spec-traceable GraphQL-over-HTTP conformance suite for {@code graphitron-jakarta-rest}, run
 * through the reference app's {@link SakilaGraphitronApplication} adapter so the reference app
 * exercises the real library and cannot drift from the spec again (the divergence R399 set out to
 * end).
 *
 * <p>Every normative requirement in the library's committed scope has a citing case below. Each
 * carries the verbatim spec sentence it verifies as a {@code @DisplayName}, a pointer to the spec
 * section, and the spec revision the text was taken from, so a future spec revision surfaces at the
 * exact failing case. The source is the
 * <a href="https://graphql.github.io/graphql-over-http/draft/">GraphQL-over-HTTP specification,
 * Working Draft</a> ({@link #SPEC_REVISION}).
 *
 * <h2>Coverage pointer table (requirement -> spec section -> test)</h2>
 * <pre>
 * | Requirement                                   | Spec section                         | Test                                  |
 * |-----------------------------------------------|--------------------------------------|---------------------------------------|
 * | POST is supported                             | "POST"                               | postIsSupported                       |
 * | GET MAY execute a query                       | "GET"                                | getMayExecuteAQuery                   |
 * | GET resolving to a mutation -> 405            | "GET"                                | getResolvingToMutationIs405           |
 * | Unparseable document -> 400                   | "application/graphql-response+json"  | unparseableDocumentIs400              |
 * | Not well-formed (missing query) -> 422        | "application/graphql-response+json"  | missingQueryIs422                     |
 * | Validation failure -> 422                     | "application/graphql-response+json"  | validationFailureIs422                |
 * | Variable coercion failure -> 422              | "application/graphql-response+json"  | variableCoercionFailureIs422          |
 * | Execution begun (field error) -> 200          | "application/graphql-response+json"  | executionWithFieldErrorIs200          |
 * | Legacy application/json -> always 200          | "application/json"                   | legacyApplicationJsonIsAlways200      |
 * | query is required                             | "Request Parameters"                 | queryParameterIsRequired              |
 * | variables/operationName/extensions handling   | "Request Parameters"                 | variablesOperationNameExtensions      |
 * </pre>
 */
@QuarkusTest
@QuarkusTestResource(SmokeTestPostgresResource.class)
@ExecutionTier
class GraphQLOverHttpConformanceTest {

    /** The spec revision every quote below was taken from. Bump with the quotes on a spec update. */
    private static final String SPEC_REVISION = "GraphQL-over-HTTP Working Draft (graphql.github.io/graphql-over-http/draft)";

    private static final String APPLICATION_JSON = "application/json";
    private static final String GRAPHQL_RESPONSE_JSON = "application/graphql-response+json";

    // ===== POST / GET method support =====

    @Test
    @DisplayName("POST [POST]: \"A server MUST support POST for executing GraphQL operations.\" -- " + SPEC_REVISION)
    void postIsSupported() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .body("{\"query\":\"{ customers { firstName } }\"}")
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.customers", notNullValue())
            .body("errors", nullValue());
    }

    @Test
    @DisplayName("GET [GET]: \"A server MAY support GET for executing GraphQL queries.\" -- " + SPEC_REVISION)
    void getMayExecuteAQuery() {
        given()
            .accept(GRAPHQL_RESPONSE_JSON)
            .queryParam("query", "{ customers { firstName } }")
        .when()
            .get("/graphql")
        .then()
            .statusCode(200)
            .body("data.customers", notNullValue())
            .body("errors", nullValue());
    }

    @Test
    @DisplayName("GET [GET]: \"GraphQL mutations SHOULD NOT be executed via GET ... the server SHOULD respond with 405 Method Not Allowed.\" -- " + SPEC_REVISION)
    void getResolvingToMutationIs405() {
        given()
            .accept(GRAPHQL_RESPONSE_JSON)
            .queryParam("query", "mutation { __typename }")
        .when()
            .get("/graphql")
        .then()
            .statusCode(405)
            .header("Allow", equalTo("POST"));
    }

    // ===== application/graphql-response+json status codes =====

    @Test
    @DisplayName("Status [application/graphql-response+json]: \"Requests where the GraphQL document cannot be parsed should result in status code 400.\" -- " + SPEC_REVISION)
    void unparseableDocumentIs400() {
        // Syntactically broken document (missing closing brace): graphql-java raises InvalidSyntax.
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .body("{\"query\":\"{ customers \"}")
        .when()
            .post("/graphql")
        .then()
            .statusCode(400);
    }

    @Test
    @DisplayName("Status [application/graphql-response+json]: \"A request that does not constitute a well-formed GraphQL-over-HTTP request SHOULD result in status code 422.\" -- " + SPEC_REVISION)
    void missingQueryIs422() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .body("{}")
        .when()
            .post("/graphql")
        .then()
            .statusCode(422);
    }

    @Test
    @DisplayName("Status [application/graphql-response+json]: \"If a request fails GraphQL validation, the server SHOULD return a status code of 422 ... without proceeding to GraphQL execution.\" -- " + SPEC_REVISION)
    void validationFailureIs422() {
        // A field that does not exist on Query fails validation before execution.
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .body("{\"query\":\"{ thisFieldDoesNotExist }\"}")
        .when()
            .post("/graphql")
        .then()
            .statusCode(422);
    }

    @Test
    @DisplayName("Status [application/graphql-response+json]: \"If CoerceVariableValues() raises a GraphQL request error, the server SHOULD NOT execute the request and SHOULD return a status code of 422.\" -- " + SPEC_REVISION)
    void variableCoercionFailureIs422() {
        // A String passed where a Boolean variable is declared fails variable coercion.
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .body("{\"query\":\"query($active: Boolean){ customers(active: $active) { firstName } }\","
                + "\"variables\":{\"active\":\"not-a-boolean\"}}")
        .when()
            .post("/graphql")
        .then()
            .statusCode(422);
    }

    @Test
    @DisplayName("Status [application/graphql-response+json]: \"Execution begins -> 200 ... This is the case even if a GraphQL field error is raised during GraphQL's ExecuteQuery().\" -- " + SPEC_REVISION)
    void executionWithFieldErrorIs200() {
        // Film.durabilityError is a @service leaf that always throws; execution begins, the field
        // error nulls the (nullable) field, data is present -> 200 with an errors array.
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .body("{\"query\":\"{ films { durabilityError } }\"}")
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("errors", notNullValue());
    }

    // ===== legacy application/json =====

    @Test
    @DisplayName("Legacy [application/json]: \"the server SHOULD use 200 status code, regardless of any GraphQL errors.\" -- " + SPEC_REVISION)
    void legacyApplicationJsonIsAlways200() {
        // The same validation failure that is 422 under application/graphql-response+json is 200
        // under the legacy application/json media type.
        given()
            .contentType(APPLICATION_JSON)
            .accept(APPLICATION_JSON)
            .body("{\"query\":\"{ thisFieldDoesNotExist }\"}")
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("errors", notNullValue());
    }

    // ===== request parameters =====

    @Test
    @DisplayName("Request Parameters: \"query ... This parameter is required.\" -- " + SPEC_REVISION)
    void queryParameterIsRequired() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .body("{\"operationName\":\"X\"}")
        .when()
            .post("/graphql")
        .then()
            .statusCode(422);
    }

    @Test
    @DisplayName("Request Parameters: \"operationName ... variables ... extensions\" are honored on the request. -- " + SPEC_REVISION)
    void variablesOperationNameExtensions() {
        // Two named operations: operationName selects A, variables drive its argument, extensions
        // are accepted and ignored. Only A's selection (customers) should run, not B's (films).
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .body("{\"query\":\"query A($active: Boolean){ customers(active: $active) { firstName } } "
                + "query B { films { title } }\","
                + "\"operationName\":\"A\","
                + "\"variables\":{\"active\":true},"
                + "\"extensions\":{\"trace\":\"ignored\"}}")
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.customers", notNullValue())
            .body("data.films", nullValue())
            .body("errors", nullValue());
    }
}
