package no.sikt.graphitron.sakila.example.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * What only a running container can show about a consumer mounting the library's delegates on its
 * own path: that the operation-policy decision reaches the wire with the right status and shape,
 * that request-scope ordering holds across the delegation, that a client fault thrown before
 * delegating still reaches the container, and that the built-in endpoint's toggle answers 404 on
 * every route it gates. The decision itself is a pure function of three arguments and is covered at
 * unit tier by {@link OperationGuardTest}; nothing here restates it.
 *
 * <p>The mount under test is {@link PolicyMountedGraphqlResource}.
 */
@QuarkusTest
@QuarkusTestResource(SmokeTestPostgresResource.class)
@ExecutionTier
class MountedEndpointTest {

    private static final String APPLICATION_JSON = "application/json";
    private static final String GRAPHQL_RESPONSE_JSON = "application/graphql-response+json";

    /** The queries-only mount: any path value that is not "production". */
    private static final String RESTRICTED = "/env/test/graphql";

    /** The unrestricted mount: the resource passes no policy for this path value. */
    private static final String UNRESTRICTED = "/env/production/graphql";

    private static final String AUTHORIZATION = "Bearer test-token";

    /** A read-only {@code @service} mutation, so the unrestricted mount can run one safely. */
    private static final String MUTATION =
        "{\"query\":\"mutation { searchManyMutation { __typename } }\"}";

    // ===== the policy on the wire =====

    @Test
    @DisplayName("A mutation on the queries-only mount is refused before execution: the policy's 400, the policy's message, no data.")
    void mutationOnRestrictedMountIsRefused() {
        Response response = given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .body(MUTATION)
        .when()
            .post(RESTRICTED)
        .then()
            .statusCode(400)
            .contentType(GRAPHQL_RESPONSE_JSON)
            .body("errors[0].message", containsString("mutation"))
            .body("data", nullValue())
        .extract().response();

        // The seam never ran, so nothing observed the holder: the refusal is before execution, not
        // a failure during it.
        assertThat(response.getHeader(PolicyMountedGraphqlResource.OBSERVED_HEADER)).isNull();
    }

    @Test
    @DisplayName("The refusal names the operation type that was actually rejected, so a subscription is not reported as a mutation.")
    void refusalNamesTheRejectedOperationType() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .body("{\"query\":\"subscription { __typename }\"}")
        .when()
            .post(RESTRICTED)
        .then()
            .statusCode(400)
            .body("errors[0].message", containsString("subscription"));
    }

    @Test
    @DisplayName("The same mutation on the mount that passes no policy executes: the policy is a per-call argument, not a mode.")
    void mutationOnUnrestrictedMountExecutes() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .body(MUTATION)
        .when()
            .post(UNRESTRICTED)
        .then()
            .statusCode(200)
            .body("data.searchManyMutation", notNullValue())
            .header(PolicyMountedGraphqlResource.OBSERVED_HEADER, equalTo("production"));
    }

    @Test
    @DisplayName("A GET resolving to a mutation is 405 with Allow: POST on either mount: the spec's rule rides the verb, not the policy.")
    void getResolvingToMutationIs405OnEitherMount() {
        // The unrestricted mount passes no policy to post(), yet its GET arm still carries the
        // specification's rule: there is no way to route a verb through the pipeline without one.
        given()
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .queryParam("query", "mutation { searchManyMutation { __typename } }")
        .when()
            .get(UNRESTRICTED)
        .then()
            .statusCode(405)
            .header("Allow", equalTo("POST"));

        // And on the queries-only mount it is 405, not the policy's 400: GET's rule, not the
        // consumer's, is what answers here.
        given()
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .queryParam("query", "mutation { searchManyMutation { __typename } }")
        .when()
            .get(RESTRICTED)
        .then()
            .statusCode(405)
            .header("Allow", equalTo("POST"));
    }

    @Test
    @DisplayName("An unresolvable operation falls through to the engine: 422 from graphql-java, not the policy's message.")
    void unresolvableOperationFallsThroughToTheEngine() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            // operationName matches no operation: the guard cannot judge it, so the engine answers.
            .body("{\"query\":\"query A { __typename } mutation B { __typename }\",\"operationName\":\"X\"}")
        .when()
            .post(RESTRICTED)
        .then()
            .statusCode(422)
            .body("errors[0].message", notNullValue());
    }

    @Test
    @DisplayName("An unparseable document on a policy-carrying mount is the pipeline's 400 parse failure.")
    void unparseableDocumentOnRestrictedMountIs400() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .body("{\"query\":\"{ customers \"}")
        .when()
            .post(RESTRICTED)
        .then()
            .statusCode(400)
            .body("errors[0].message", containsString("could not be parsed"));
    }

    @Test
    @DisplayName("A policy rejection is not downgraded for a legacy client: still 400, error body in application/json.")
    void policyRejectionIsNotLegacyDowngraded() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(APPLICATION_JSON)
            .header("Authorization", AUTHORIZATION)
            .body(MUTATION)
        .when()
            .post(RESTRICTED)
        .then()
            // An HTTP-level rule, not a GraphQL request error, so the legacy always-200 rule does
            // not apply. Only the body's media type follows negotiation.
            .statusCode(400)
            .contentType(APPLICATION_JSON)
            .body("errors[0].message", containsString("mutation"));
    }

    // ===== the templated mount's own surface =====

    @Test
    @DisplayName("The GraphiQL page under a templated mount resolves its assets against the concrete request path.")
    void graphiqlPageResolvesAssetsUnderTheTemplatedMount() {
        var page = given()
            .accept("text/html")
        .when()
            .get(RESTRICTED)
        .then()
            .statusCode(200)
        .extract().asString();

        assertThat(page).doesNotContain("{{ASSET_BASE}}");
        assertThat(page).contains("/env/test/graphql/assets/graphiql.js");

        given()
        .when()
            .get(RESTRICTED + "/assets/graphiql.js")
        .then()
            .statusCode(200)
            .contentType(startsWith("text/javascript"));
    }

    @Test
    @DisplayName("The SDL endpoint under a templated mount returns the schema.")
    void schemaUnderTheTemplatedMountReturnsSdl() {
        var sdl = given()
        .when()
            .get(RESTRICTED + "/schema")
        .then()
            .statusCode(200)
        .extract().asString();

        assertThat(sdl).contains("type Query");
    }

    // ===== ordering and the exception passthrough =====

    @Test
    @DisplayName("The seam observes what the resource method set before delegating: request scope survives the delegation.")
    void seamObservesWhatTheResourceSet() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .body("{\"query\":\"{ customers { firstName } }\"}")
        .when()
            .post(RESTRICTED)
        .then()
            .statusCode(200)
            // The adapter recorded the holder's contents from inside newExecutionInput(); an absent
            // header here would mean the seam ran before the resource populated it.
            .header(PolicyMountedGraphqlResource.OBSERVED_HEADER, equalTo("test"));
    }

    @Test
    @DisplayName("A WebApplicationException thrown by the resource method before delegating surfaces as its own status, unredacted.")
    void clientFaultBeforeDelegationSurfaces() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            // No Authorization header: the mounted resource throws NotAuthorizedException.
            .body("{\"query\":\"{ customers { firstName } }\"}")
        .when()
            .post(RESTRICTED)
        .then()
            .statusCode(401);
    }

    // ===== turning the built-in endpoint off =====

    @Test
    @DisplayName("With builtInEndpointEnabled() false all five built-in routes answer 404, while the mounted endpoint keeps working.")
    void builtInEndpointCanBeTurnedOff() {
        String off = FaultInjectingGraphitronApplication.BUILT_IN_HEADER;

        given().header(off, "off")
            .contentType(APPLICATION_JSON).accept(GRAPHQL_RESPONSE_JSON)
            .body("{\"query\":\"{ customers { firstName } }\"}")
            .when().post("/graphql").then().statusCode(404);

        given().header(off, "off")
            .accept(GRAPHQL_RESPONSE_JSON).queryParam("query", "{ customers { firstName } }")
            .when().get("/graphql").then().statusCode(404);

        given().header(off, "off")
            .accept("text/html")
            .when().get("/graphql").then().statusCode(404);

        given().header(off, "off")
            .when().get("/graphql/assets/graphiql.js").then().statusCode(404);

        // The one whose gate has to throw rather than return, because schema() returns String.
        given().header(off, "off")
            .when().get("/graphql/schema").then().statusCode(404);

        // The consumer's own mount is unaffected: the toggle gates the library's resource, not the
        // delegates the library and the consumer share.
        given().header(off, "off")
            .contentType(APPLICATION_JSON).accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .body("{\"query\":\"{ customers { firstName } }\"}")
            .when().post(RESTRICTED).then().statusCode(200);
    }
}
