package no.sikt.graphitron.sakila.example.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@code GraphqlHttpHandler} bounds the numbers in a request's {@code variables} and
 * {@code extensions}, and the integer and float tokens of its document: one whose plain decimal form
 * has more than a thousand digits is a {@code 422} request error on either verb, answered before the
 * engine parses or coerces anything, while ordinary integer, decimal and exponent values execute as
 * before.
 *
 * <p>The out-of-bound cases use exponents far past the bound and run under a preemptive timeout,
 * so a regression shows as a failed case rather than a stalled build. The boundary cases sit
 * either side of the bound with exponents small enough to coerce cheaply, and tell the handler's
 * refusal from the engine's own by its message.
 *
 * <p>Numeric text is not a number to the handler, so it passes, in a string variable and in a
 * string literal alike, and the sink bounds it: a column-bound {@code ID} coerces through
 * {@code ColumnValues.fromWire}, which answers an over-large value as no value of the column, so
 * the filter matches nothing, promptly, while an ordinary key still matches; and a scalar declared
 * with {@code @scalarType} is registered behind the generated schema's {@code BoundedCoercing},
 * which refuses a number past the bound as a coercion error before the scalar's own coercing parses
 * it, however the client wrote it.
 */
@QuarkusTest
@QuarkusTestResource(SmokeTestPostgresResource.class)
@ResourceLock(QuarkusTestLock.KEY)
@ExecutionTier
class RequestNumberBoundsTest {

    private static final String APPLICATION_JSON = "application/json";
    private static final String GRAPHQL_RESPONSE_JSON = "application/graphql-response+json";
    private static final String REFUSAL = "digits written out in full";
    private static final Duration PROMPTLY = Duration.ofSeconds(10);

    private static final String INT_QUERY = "query($n: Int) { storeById(store_id: [1]) { customersFirstN(first: $n) { firstName } } }";
    // The engine coerces every declared variable before execution, so the skipped field still
    // carries the Float through coercion without running its fetcher.
    private static final String FLOAT_QUERY =
        "query($r: Float) { films(maxRentalRate: $r) @skip(if: true) { title } }";
    private static final String ID_QUERY = "query($id: ID!) { node(id: $id) { __typename } }";
    private static final String BIG_DECIMAL_QUERY =
        "query($f: FilmRentalRangeFilter) { filmsByRentalRateRange(filter: $f) { title } }";
    // store_id is an int column on both participants, so the ID text coerces at the column.
    private static final String STORE_ID_QUERY =
        "query($s: [ID!]) { occupantsByStoreId(storeId: $s) { __typename } }";
    private static final String MANY_DIGITS = "7".repeat(1_000_000);

    // ===== out of bounds, every variable type, both verbs =====

    @ParameterizedTest(name = "POST, {0}")
    @ValueSource(strings = {"1e999999999", "-1e999999999", "1e-999999999"})
    @DisplayName("An Int, Float, ID or custom-scalar variable far out of bounds is a prompt 422 over POST.")
    void outOfBoundsVariablesArePost422(String number) {
        for (String[] request : variableRequests(number)) {
            promptly(() -> post(body(request[0], request[1], null), GRAPHQL_RESPONSE_JSON))
                .statusCode(422)
                .body("errors[0].message", containsString(REFUSAL))
                .body("data", nullValue());
        }
    }

    @ParameterizedTest(name = "GET, {0}")
    @ValueSource(strings = {"1e999999999", "-1e999999999", "1e-999999999"})
    @DisplayName("An Int, Float, ID or custom-scalar variable far out of bounds is a prompt 422 over GET.")
    void outOfBoundsVariablesAreGet422(String number) {
        for (String[] request : variableRequests(number)) {
            promptly(() -> get(request[0], request[1], null))
                .statusCode(422)
                .body("errors[0].message", containsString(REFUSAL));
        }
    }

    @Test
    @DisplayName("A number nested in a list inside an object in 'extensions' is bounded too, on both verbs.")
    void nestedExtensionsAreBounded() {
        String extensions = "{\"trace\":{\"spans\":[1,2,{\"at\":1e999999999}]}}";
        promptly(() -> post(body("{ __typename }", null, extensions), GRAPHQL_RESPONSE_JSON))
            .statusCode(422)
            .body("errors[0].message", containsString(REFUSAL));
        promptly(() -> get("{ __typename }", null, extensions))
            .statusCode(422)
            .body("errors[0].message", containsString(REFUSAL));
    }

    @Test
    @DisplayName("Under legacy application/json the refusal is 200 with the same error body.")
    void legacyRefusalIs200() {
        promptly(() -> post(body(INT_QUERY, "{\"n\":1e999999999}", null), APPLICATION_JSON))
            .statusCode(200)
            .body("errors[0].message", containsString(REFUSAL));
    }

    // ===== the bound itself =====

    @Test
    @DisplayName("A thousand digits written out is within the bound; a thousand and one is not.")
    void boundIsAThousandDigits() {
        // 1e999 is a 1 and 999 zeros: within the bound, so the engine coerces it and refuses it as
        // out of Int range with its own message.
        post(body(INT_QUERY, "{\"n\":1e999}", null), GRAPHQL_RESPONSE_JSON)
            .statusCode(422)
            .body("errors[0].message", not(containsString(REFUSAL)));
        post(body(INT_QUERY, "{\"n\":1e1000}", null), GRAPHQL_RESPONSE_JSON)
            .statusCode(422)
            .body("errors[0].message", containsString(REFUSAL));
        // The same on the fraction side: 999 zeros then a 1 after the point is 1000 digits.
        post(body(FLOAT_QUERY, "{\"r\":1e-1000}", null), GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue());
        post(body(FLOAT_QUERY, "{\"r\":1e-1001}", null), GRAPHQL_RESPONSE_JSON)
            .statusCode(422)
            .body("errors[0].message", containsString(REFUSAL));
    }

    @Test
    @DisplayName("A literal with more than a thousand significant digits is refused without an exponent.")
    void longLiteralIsRefused() {
        String digits = "9".repeat(1001);
        post(body(FLOAT_QUERY, "{\"r\":" + digits + "}", null), GRAPHQL_RESPONSE_JSON)
            .statusCode(422)
            .body("errors[0].message", containsString(REFUSAL));
    }

    // ===== numeric text is bounded where it is parsed =====

    @ParameterizedTest(name = "variable {index}")
    @ValueSource(strings = {"1e999999999", "-1e999999999", "9e-999999999", "1E+999999999"})
    @DisplayName("An ID variable holding extreme exponent text passes the handler and matches nothing, promptly.")
    void exponentTextInAnIdVariableMatchesNothing(String text) {
        promptly(() -> post(body(STORE_ID_QUERY, "{\"s\":[\"" + text + "\"]}", null), GRAPHQL_RESPONSE_JSON))
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.occupantsByStoreId", hasSize(0));
    }

    @Test
    @DisplayName("An ID variable holding a million digits matches nothing, promptly.")
    void longDigitTextInAnIdVariableMatchesNothing() {
        promptly(() -> post(body(STORE_ID_QUERY, "{\"s\":[\"" + MANY_DIGITS + "\"]}", null), GRAPHQL_RESPONSE_JSON))
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.occupantsByStoreId", hasSize(0));
    }

    @Test
    @DisplayName("The same text written as a literal in the document matches nothing, promptly, on both verbs.")
    void numericTextInADocumentLiteralMatchesNothing() {
        String exponent = "{ occupantsByStoreId(storeId: [\"1e999999999\"]) { __typename } }";
        promptly(() -> post(body(exponent, null, null), GRAPHQL_RESPONSE_JSON))
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.occupantsByStoreId", hasSize(0));
        promptly(() -> get(exponent, null, null))
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.occupantsByStoreId", hasSize(0));
        // An ID literal written as an integer reaches the column as its text too, where the token
        // is within the handler's bound on a document number.
        String digits = "7".repeat(1_000);
        promptly(() -> post(body("{ occupantsByStoreId(storeId: [" + digits + "]) { __typename } }", null, null),
                GRAPHQL_RESPONSE_JSON))
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.occupantsByStoreId", hasSize(0));
        promptly(() -> post(body("{ occupantsByStoreId(storeId: [\"" + MANY_DIGITS + "\"]) { __typename } }", null, null),
                GRAPHQL_RESPONSE_JSON))
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.occupantsByStoreId", hasSize(0));
    }

    @Test
    @DisplayName("An ordinary store key, as a variable and as a literal, still matches.")
    void ordinaryIdTextStillMatches() {
        post(body(STORE_ID_QUERY, "{\"s\":[\"1\"]}", null), GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.occupantsByStoreId", not(hasSize(0)));
        post(body("{ occupantsByStoreId(storeId: [\"1\"]) { __typename } }", null, null), GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.occupantsByStoreId", not(hasSize(0)));
    }

    // ===== a number in the document is bounded as a JSON number is =====

    @Test
    @DisplayName("An integer or float token of the document far out of bounds is a prompt 422.")
    void longDocumentNumberIsRefused() {
        // Long enough that the engine's parser, unbounded, spends seconds building the number.
        String digits = "7".repeat(400_000);
        for (String document : new String[] {
                "{ occupantsByStoreId(storeId: [" + digits + "]) { __typename } }",
                "{ occupantsByStoreId(storeId: [-" + digits + "]) { __typename } }",
                "{ films(maxRentalRate: " + digits + ".5) @skip(if: true) { title } }",
                "{ films(maxRentalRate: 0." + digits + ") @skip(if: true) { title } }",
                "{ films(maxRentalRate: 1e999999999) @skip(if: true) { title } }",
                "{ films(maxRentalRate: 1E-999999999) @skip(if: true) { title } }",
                "query($r: Float = " + digits + ".5) { films(maxRentalRate: $r) @skip(if: true) { title } }"}) {
            promptly(() -> post(body(document, null, null), GRAPHQL_RESPONSE_JSON))
                .statusCode(422)
                .body("errors[0].message", containsString(REFUSAL));
        }
        promptly(() -> post(body("{ occupantsByStoreId(storeId: [" + digits + "]) { __typename } }", null, null),
                APPLICATION_JSON))
            .statusCode(200)
            .body("errors[0].message", containsString(REFUSAL));
    }

    @Test
    @DisplayName("A thousand digits in a document number is within the bound; a thousand and one is not, on both verbs.")
    void documentBoundIsAThousandDigits() {
        String within = "{ occupantsByStoreId(storeId: [" + "7".repeat(1_000) + "]) { __typename } }";
        String beyond = "{ occupantsByStoreId(storeId: [" + "7".repeat(1_001) + "]) { __typename } }";
        post(body(within, null, null), GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue());
        get(within, null, null)
            .statusCode(200)
            .body("errors", nullValue());
        post(body(beyond, null, null), GRAPHQL_RESPONSE_JSON)
            .statusCode(422)
            .body("errors[0].message", containsString(REFUSAL));
        get(beyond, null, null)
            .statusCode(422)
            .body("errors[0].message", containsString(REFUSAL));
        // The same on the exponent's side: a 1 and 999 zeros passes the handler, and the engine
        // refuses it as no Float with its own message; one zero more is the handler's to refuse.
        post(body("{ films(maxRentalRate: 1e999) @skip(if: true) { title } }", null, null), GRAPHQL_RESPONSE_JSON)
            .body("errors[0].message", not(containsString(REFUSAL)));
        post(body("{ films(maxRentalRate: 1e1000) @skip(if: true) { title } }", null, null), GRAPHQL_RESPONSE_JSON)
            .statusCode(422)
            .body("errors[0].message", containsString(REFUSAL));
    }

    @Test
    @DisplayName("Digits in a string, a block string, a comment or a name of the document are not a number.")
    void digitsThatAreNoDocumentNumberPass() {
        String digits = "7".repeat(1_500);
        // The newline that ends the comment is written as its JSON escape, since body() escapes quotes only.
        String document = "# " + digits + "\\n"
            + "{ a" + digits + ": occupantsByStoreId(storeId: [\"" + digits + "\", \"\"\"" + digits + "\"\"\"])"
            + " { __typename } }";
        post(body(document, null, null), GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.a" + digits, hasSize(0));
    }

    // ===== a custom scalar is handed no number past the bound, however it is written =====

    @ParameterizedTest(name = "text {index}")
    @ValueSource(strings = {"1e999999999", "-1e999999999", "1e-999999999", " 1E+999999999 "})
    @DisplayName("Exponent text in a string variable for a custom scalar is a prompt coercion error.")
    void exponentTextForACustomScalarIsRefused(String text) {
        promptly(() -> post(body(BIG_DECIMAL_QUERY,
                "{\"f\":{\"rentalRateRange\":{\"fra\":\"" + text + "\",\"til\":\"3.00\"}}}", null), GRAPHQL_RESPONSE_JSON))
            .body("data", nullValue())
            .body("errors[0].message", containsString(REFUSAL));
    }

    @Test
    @DisplayName("A long run of digits in a string variable for a custom scalar is a prompt coercion error.")
    void longDigitTextForACustomScalarIsRefused() {
        promptly(() -> post(body(BIG_DECIMAL_QUERY,
                "{\"f\":{\"rentalRateRange\":{\"fra\":\"" + MANY_DIGITS + "\",\"til\":\"3.00\"}}}", null),
                GRAPHQL_RESPONSE_JSON))
            .body("data", nullValue())
            .body("errors[0].message", containsString(REFUSAL));
    }

    @Test
    @DisplayName("The same text in a string literal of the document is refused before the scalar parses it.")
    void numericTextInALiteralForACustomScalarIsRefused() {
        // The engine quotes the refused literal in its error, so the answer is as long as the literal.
        String document = "{ filmsByRentalRateRange(filter: {rentalRateRange: {fra: \"" + MANY_DIGITS + "\", til: \"3.00\"}})"
            + " { title } }";
        ValidatableResponse response = assertTimeoutPreemptively(PROMPTLY,
            () -> post(body(document, null, null), GRAPHQL_RESPONSE_JSON));
        response.body("data", nullValue())
            .body("errors[0].message", containsString(REFUSAL));
    }

    @Test
    @DisplayName("Ordinary numbers written as text still reach a custom scalar that reads them.")
    void ordinaryTextForACustomScalarStillCoerces() {
        post(body(BIG_DECIMAL_QUERY, "{\"f\":{\"rentalRateRange\":{\"fra\":\"2.00\",\"til\":\"3.00\"}}}", null),
                GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.filmsByRentalRateRange", notNullValue());
        post(body("{ filmsByRentalRateRange(filter: {rentalRateRange: {fra: \"2.00\", til: 3.00}}) { title } }",
                null, null), GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.filmsByRentalRateRange", notNullValue());
    }

    // ===== ordinary values coerce as before =====

    @Test
    @DisplayName("Ordinary integer, decimal and exponent values still execute, on both verbs.")
    void ordinaryValuesExecute() {
        post(body(INT_QUERY, "{\"n\":2}", null), GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.storeById[0].customersFirstN", hasSize(2));
        // An exponent the engine reads as the integer 2.
        get(INT_QUERY, "{\"n\":2e0}", null)
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.storeById[0].customersFirstN", hasSize(2));
        post(body(FLOAT_QUERY, "{\"r\":2.99}", null), GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue())
            .body("data", notNullValue());
        post(body(FLOAT_QUERY, "{\"r\":2.99e0}", null), GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue());
        post(body(BIG_DECIMAL_QUERY, "{\"f\":{\"rentalRateRange\":{\"fra\":2.00,\"til\":3.00}}}", null),
                GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue())
            .body("data.filmsByRentalRateRange", notNullValue());
    }

    // ===== plumbing =====

    /** One {query, variables} pair per variable type, each carrying {@code number}. */
    private static String[][] variableRequests(String number) {
        return new String[][] {
            {INT_QUERY, "{\"n\":" + number + "}"},
            {FLOAT_QUERY, "{\"r\":" + number + "}"},
            {ID_QUERY, "{\"id\":" + number + "}"},
            {BIG_DECIMAL_QUERY, "{\"f\":{\"rentalRateRange\":{\"fra\":" + number + "}}}"},
        };
    }

    private static String body(String query, String variables, String extensions) {
        StringBuilder json = new StringBuilder("{\"query\":\"").append(query.replace("\"", "\\\"")).append('"');
        if (variables != null) {
            json.append(",\"variables\":").append(variables);
        }
        if (extensions != null) {
            json.append(",\"extensions\":").append(extensions);
        }
        return json.append('}').toString();
    }

    private static ValidatableResponse post(String body, String accept) {
        return given()
            .contentType(APPLICATION_JSON)
            .accept(accept)
            .body(body)
        .when()
            .post("/graphql")
        .then();
    }

    private static ValidatableResponse get(String query, String variables, String extensions) {
        var request = given().accept(GRAPHQL_RESPONSE_JSON).queryParam("query", query);
        if (variables != null) {
            request.queryParam("variables", variables);
        }
        if (extensions != null) {
            request.queryParam("extensions", extensions);
        }
        return request.when().get("/graphql").then();
    }

    private static ValidatableResponse promptly(Supplier<ValidatableResponse> call) {
        ValidatableResponse response = assertTimeoutPreemptively(PROMPTLY, call::get);
        assertThat(response.extract().body().asString()).hasSizeLessThan(1024);
        return response;
    }
}
