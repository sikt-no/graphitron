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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * What {@code GraphqlHttpHandler}'s bound on the numbers of a GraphQL document rests on, beside
 * the bound itself that {@link RequestNumberBoundsTest} pins: that a number is found where the
 * engine's own lexer finds one, that the numbers of a document are bounded together as well as
 * singly, and that the refusal comes before the input seam on every way in.
 *
 * <p>The parser builds a number at a cost that grows with the square of its length, and its lexer
 * is slow on every digit of one, so the long cases run under a preemptive timeout: a document that
 * reaches the parser unchecked takes several times that, and shows as a failed case rather than a
 * stalled build. Each case tells the handler's refusal from anything the engine says by its
 * message.
 */
@QuarkusTest
@QuarkusTestResource(SmokeTestPostgresResource.class)
@ResourceLock(QuarkusTestLock.KEY)
@ExecutionTier
class DocumentNumberBoundsTest {

    private static final String APPLICATION_JSON = "application/json";
    private static final String GRAPHQL_RESPONSE_JSON = "application/graphql-response+json";

    /** One fragment per refusal; {@link #HANDLER} is common to both and to nothing the engine says. */
    private static final String WRITTEN_OUT = "digits written out in full";
    private static final String IN_ALL = "characters in all";
    private static final String HANDLER = "GraphQL document";

    private static final Duration PROMPTLY = Duration.ofSeconds(10);

    /** The mounts of {@link PolicyMountedGraphqlResource}: one passes a policy to POST, one does not. */
    private static final String RESTRICTED = "/env/test/graphql";
    private static final String UNRESTRICTED = "/env/production/graphql";
    private static final String AUTHORIZATION = "Bearer test-token";

    /** Long enough that one unchecked parse outlasts {@link #PROMPTLY}, short of the parser's character cap. */
    private static final String VERY_LONG = "9".repeat(900_000);

    // ===== a number is where the lexer says one is =====

    @Test
    @DisplayName("A block string closed by the quotes of an escaped triple quote does not hide the number after it.")
    void numberAfterABlockStringClosedByAnEscapeIsRefused() {
        // No triple quote follows, so the lexer ends the string at the quotes of \""" and reads the
        // digits as a number. Read as an escape, the same quotes would leave the digits in a string.
        String document = "{ occupantsByStoreId(storeId: [\"\"\"abc\\\"\"\" " + VERY_LONG + "]) { __typename } }";
        promptly(() -> post("/graphql", document, GRAPHQL_RESPONSE_JSON))
            .statusCode(422)
            .body("errors[0].message", containsString(WRITTEN_OUT))
            .body("data", nullValue());
    }

    @Test
    @DisplayName("The same digits before a closing triple quote are the string's, and pass.")
    void digitsInsideABlockStringHoldingAnEscapePass() {
        String document = "{ occupantsByStoreId(storeId: [\"\"\"abc\\\"\"\" " + "9".repeat(1_500) + " \"\"\"]) { __typename } }";
        assertThat(bodyOf(post("/graphql", document, GRAPHQL_RESPONSE_JSON))).doesNotContain(HANDLER);
    }

    @Test
    @DisplayName("A comment ends at a carriage return, so a quote in it opens no string and the number on the next line is refused.")
    void numberAfterACommentHoldingAQuoteIsRefused() {
        String document = "{ occupantsByStoreId(storeId: [ # \"\r " + VERY_LONG + "]) { __typename } }";
        promptly(() -> post("/graphql", document, GRAPHQL_RESPONSE_JSON))
            .statusCode(422)
            .body("errors[0].message", containsString(WRITTEN_OUT));
    }

    @Test
    @DisplayName("More than a thousand digits in a row in any part of a number is refused, whatever the number comes to.")
    void longRunInAnyPartOfANumberIsRefused() {
        // Both are small numbers written long: an exponent of 1 behind a thousand and one zeros, and
        // a zero with a long fraction scaled back by its exponent.
        for (String literal : new String[] {"1e" + "0".repeat(1_001) + "1", "0." + "0".repeat(1_500) + "e1000"}) {
            post("/graphql", floatQuery(literal), GRAPHQL_RESPONSE_JSON)
                .statusCode(422)
                .body("errors[0].message", containsString(WRITTEN_OUT));
        }
        assertThat(bodyOf(post("/graphql", floatQuery("1e" + "0".repeat(999) + "1"), GRAPHQL_RESPONSE_JSON)))
            .doesNotContain(HANDLER);
    }

    // ===== the numbers of a document, together =====

    @Test
    @DisplayName("Integer and float tokens may be written with ten thousand characters between them, and no more.")
    void numbersAreBoundedInAll() {
        // 2000 five-character tokens are exactly the bound.
        String atTheBound = "12345 ".repeat(2_000);
        post("/graphql", storesQuery(atTheBound), GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue());
        post("/graphql", storesQuery(atTheBound + "1"), GRAPHQL_RESPONSE_JSON)
            .statusCode(422)
            .body("errors[0].message", containsString(IN_ALL));
    }

    @Test
    @DisplayName("A document that is mostly numbers is a prompt 422 though none of them is long.")
    void aDocumentOfNumbersIsRefusedPromptly() {
        promptly(() -> post("/graphql", storesQuery(("9".repeat(1_000) + " ").repeat(900)), GRAPHQL_RESPONSE_JSON))
            .statusCode(422)
            .body("errors[0].message", containsString(IN_ALL));
        promptly(() -> post("/graphql", storesQuery("1 ".repeat(450_000)), APPLICATION_JSON))
            .statusCode(200)
            .body("errors[0].message", containsString(IN_ALL));
    }

    @Test
    @DisplayName("Digits in strings, comments and names count toward nothing.")
    void digitsThatAreNoNumberAreNotCounted() {
        String digits = "7".repeat(20_000);
        String document = "# " + digits + "\n{ a" + digits + ": occupantsByStoreId(storeId: [\"" + digits + "\"]) { __typename } }";
        post("/graphql", document, GRAPHQL_RESPONSE_JSON)
            .statusCode(200)
            .body("errors", nullValue());
    }

    // ===== before the input seam, every way in =====

    @ParameterizedTest(name = "POST {0}")
    @ValueSource(strings = {RESTRICTED, UNRESTRICTED})
    @DisplayName("On a mounted endpoint, with a policy or without, the refusal comes before the input seam runs.")
    void refusalNeverReachesTheSeam(String mount) {
        for (String document : new String[] {intQuery(VERY_LONG), storesQuery("12345 ".repeat(2_001))}) {
            ValidatableResponse response = promptly(() -> post(mount, document, GRAPHQL_RESPONSE_JSON))
                .statusCode(422)
                .body("errors[0].message", containsString(HANDLER));
            assertThat(response.extract().header(PolicyMountedGraphqlResource.OBSERVED_HEADER)).isNull();
        }
    }

    @Test
    @DisplayName("An unparseable document carrying a number is still the engine's syntax error, not a refusal.")
    void unparseableDocumentIsNotARefusal() {
        ValidatableResponse response = post("/graphql", "{ storeById(store_id: [1]) ` { 12 ", GRAPHQL_RESPONSE_JSON)
            .statusCode(400);
        assertThat(bodyOf(response)).doesNotContain(HANDLER);
    }

    // ===== plumbing =====

    private static String intQuery(String literal) {
        return "{ storeById(store_id: [1]) { customersFirstN(first: " + literal + ") { firstName } } }";
    }

    // The skipped field is validated, literal included, without running its fetcher.
    private static String floatQuery(String literal) {
        return "{ films(maxRentalRate: " + literal + ") @skip(if: true) { title } }";
    }

    private static String storesQuery(String literals) {
        return "{ storeById(store_id: [" + literals + "]) @skip(if: true) { __typename } }";
    }

    private static ValidatableResponse post(String path, String query, String accept) {
        String json = query.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        return given()
            .contentType(APPLICATION_JSON)
            .accept(accept)
            .header("Authorization", AUTHORIZATION)
            .body("{\"query\":\"" + json + "\"}")
        .when()
            .post(path)
        .then();
    }

    private static String bodyOf(ValidatableResponse response) {
        return response.extract().body().asString();
    }

    private static ValidatableResponse promptly(Supplier<ValidatableResponse> call) {
        ValidatableResponse response = assertTimeoutPreemptively(PROMPTLY, call::get);
        assertThat(bodyOf(response)).hasSizeLessThan(1024);
        return response;
    }
}
