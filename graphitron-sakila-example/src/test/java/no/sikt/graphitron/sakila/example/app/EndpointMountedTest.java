package no.sikt.graphitron.sakila.example.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * What only a running container can show about {@link EndpointMountedGraphqlResource}, a resource
 * that returns {@code GraphqlEndpoint.Request} from a locator and builds each request's input where
 * the request is in hand: that execution, GraphiQL, assets and the SDL are all served under a
 * templated path of the consumer's own; that per-request values reach the input with no scoped bean
 * between the resource and the engine, concurrent requests included; that the input is built only
 * when an operation executes; and how a rejected session identity reaches the application's hooks.
 */
@QuarkusTest
@QuarkusTestResource(SmokeTestPostgresResource.class)
@ResourceLock(QuarkusTestLock.KEY)
@ExecutionTier
class EndpointMountedTest {

    private static final String APPLICATION_JSON = "application/json";
    private static final String GRAPHQL_RESPONSE_JSON = "application/graphql-response+json";
    private static final String AUTHORIZATION = "Bearer test-token";

    private static final String RESTRICTED = "/endpoint/test/graphql";
    private static final String UNRESTRICTED = "/endpoint/production/graphql";

    private static final String MUTATION =
        "{\"query\":\"mutation { searchManyMutation { __typename } }\"}";

    private static String path(String caller) {
        return "/endpoint/" + caller + "/graphql";
    }

    // ===== the input carries this request's values =====

    @Test
    @DisplayName("The path parameter and the header the resource read reach the generated factory: the schema's userId and the mounted claims echo back.")
    void perRequestValuesReachTheInput() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .body("{\"query\":\"{ greetingByUser claimsEcho }\"}")
        .when()
            .post(path("alice"))
        .then()
            .statusCode(200)
            .body("data.greetingByUser", equalTo("hello alice"))
            .body("data.claimsEcho", equalTo("{\"sub\":\"alice\"}"));
    }

    @Test
    @DisplayName("Concurrent requests each see their own values: nothing is held between the resource and the engine.")
    void concurrentRequestsSeeTheirOwnValues() throws Exception {
        int requests = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                String caller = "caller" + i;
                calls.add(() -> given()
                    .contentType(APPLICATION_JSON)
                    .accept(GRAPHQL_RESPONSE_JSON)
                    .header("Authorization", AUTHORIZATION)
                    .body("{\"query\":\"{ greetingByUser claimsEcho }\"}")
                .when()
                    .post(path(caller))
                .then()
                    .statusCode(200)
                    .body("data.claimsEcho", equalTo("{\"sub\":\"" + caller + "\"}"))
                .extract().path("data.greetingByUser"));
            }
            List<Future<String>> results = pool.invokeAll(calls);
            for (int i = 0; i < requests; i++) {
                assertThat(results.get(i).get()).isEqualTo("hello caller" + i);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("GET carries the same values through the same supplier, and a GET mutation is still 405 with Allow: POST.")
    void getUsesTheSameSupplier() {
        given()
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .queryParam("query", "{ greetingByUser }")
        .when()
            .get(path("bob"))
        .then()
            .statusCode(200)
            .body("data.greetingByUser", equalTo("hello bob"));

        given()
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .queryParam("query", "mutation { searchManyMutation { __typename } }")
        .when()
            .get(UNRESTRICTED)
        .then()
            .statusCode(405)
            .header("Allow", equalTo("POST"));
    }

    // ===== the surface beside execution =====

    @Test
    @DisplayName("GraphiQL, its assets and the SDL are served under the templated path with no authentication: the supplier is not called for them.")
    void graphiqlAssetsAndSdlNeedNoAuthentication() {
        var page = given()
            .accept("text/html")
        .when()
            .get(path("carol"))
        .then()
            .statusCode(200)
        .extract().asString();
        assertThat(page).doesNotContain("{{ASSET_BASE}}");
        assertThat(page).contains("/endpoint/carol/graphql/assets/graphiql.js");

        given().when().get(path("carol") + "/assets/graphiql.js")
            .then().statusCode(200).contentType(startsWith("text/javascript"));

        given().when().get(path("carol") + "/schema")
            .then().statusCode(200).body(containsString("type Query"));
    }

    @Test
    @DisplayName("The built-in endpoint's toggle does not reach a mounted endpoint; the GraphiQL toggle does, for the page and its assets.")
    void togglesAreScopedToWhatTheyGovern() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .header(FaultInjectingGraphitronApplication.BUILT_IN_HEADER, "off")
            .body("{\"query\":\"{ greetingByUser }\"}")
        .when()
            .post(path("dave"))
        .then()
            .statusCode(200)
            .body("data.greetingByUser", equalTo("hello dave"));

        given()
            .accept("text/html")
            .header(FaultInjectingGraphitronApplication.GRAPHIQL_HEADER, "off")
        .when()
            .get(path("dave"))
        .then()
            .statusCode(404);

        given()
            .header(FaultInjectingGraphitronApplication.GRAPHIQL_HEADER, "off")
        .when()
            .get(path("dave") + "/assets/graphiql.js")
        .then()
            .statusCode(404);
    }

    // ===== faults and the policy, relative to the supplier =====

    @Test
    @DisplayName("A NotAuthorizedException from the supplier reaches the container with the resource's status, unredacted.")
    void missingAuthenticationIs401() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .body("{\"query\":\"{ greetingByUser }\"}")
        .when()
            .post(path("erin"))
        .then()
            .statusCode(401)
            .header("WWW-Authenticate", "Bearer");
    }

    @Test
    @DisplayName("The policy refuses a mutation before the supplier runs; the unrestricted endpoint runs it.")
    void policyIsAppliedBeforeTheSupplier() {
        // No Authorization header: were the supplier called, this would be the supplier's 401.
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .body(MUTATION)
        .when()
            .post(RESTRICTED)
        .then()
            .statusCode(400)
            .body("errors[0].message", containsString("mutation"))
            .body("data", nullValue());

        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .body(MUTATION)
        .when()
            .post(UNRESTRICTED)
        .then()
            .statusCode(200)
            .body("data.searchManyMutation", notNullValue());
    }

    // ===== a rejected identity =====

    private static final String QUERY_TOUCHING_THE_DATABASE = "{\"query\":\"{ filmsOrdered { title } }\"}";

    @Test
    @DisplayName("A rejected mount reaches rejectedIdentity with the mount's own exception, on POST and on GET, and its answer is unredacted.")
    void rejectedIdentityReceivesTheMountsOwnException() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .header(EndpointMountedGraphqlResource.REJECT_HEADER, "yes")
            .header(FaultInjectingGraphitronApplication.REJECTED_IDENTITY_HEADER,
                FaultInjectingGraphitronApplication.HOOK_ANSWERS)
            .body(QUERY_TOUCHING_THE_DATABASE)
        .when()
            .post(path("frank"))
        .then()
            .statusCode(401)
            .header(FaultInjectingGraphitronApplication.ANSWERED_BY_HEADER, equalTo("rejectedIdentity"))
            .header(FaultInjectingGraphitronApplication.CAUSE_CLASS_HEADER, notNullValue())
            .header(FaultInjectingGraphitronApplication.CAUSE_CLASS_HEADER, not(containsString("SessionMountException")));

        given()
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .header(EndpointMountedGraphqlResource.REJECT_HEADER, "yes")
            .header(FaultInjectingGraphitronApplication.REJECTED_IDENTITY_HEADER,
                FaultInjectingGraphitronApplication.HOOK_ANSWERS)
            .queryParam("query", "{ filmsOrdered { title } }")
        .when()
            .get(path("frank"))
        .then()
            .statusCode(401)
            .header(FaultInjectingGraphitronApplication.ANSWERED_BY_HEADER, equalTo("rejectedIdentity"));
    }

    @Test
    @DisplayName("An adapter overriding both hooks gets rejectedIdentity for a mount failure and clientFault for the rest; a declining rejectedIdentity falls through to clientFault.")
    void rejectedIdentityPrecedesClientFault() {
        // Both hooks would answer here; the 401 is rejectedIdentity's, clientFault's would be a 403.
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .header(EndpointMountedGraphqlResource.REJECT_HEADER, "yes")
            .header(FaultInjectingGraphitronApplication.REJECTED_IDENTITY_HEADER,
                FaultInjectingGraphitronApplication.HOOK_ANSWERS)
            .body(QUERY_TOUCHING_THE_DATABASE)
        .when()
            .post(path("gina"))
        .then()
            .statusCode(401);

        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .header(EndpointMountedGraphqlResource.REJECT_HEADER, "yes")
            .header(FaultInjectingGraphitronApplication.REJECTED_IDENTITY_HEADER,
                FaultInjectingGraphitronApplication.HOOK_DECLINES)
            .body(QUERY_TOUCHING_THE_DATABASE)
        .when()
            .post(path("gina"))
        .then()
            .statusCode(403)
            .header(FaultInjectingGraphitronApplication.ANSWERED_BY_HEADER, equalTo("clientFault"));
    }

    @Test
    @DisplayName("A rejected mount no hook answers is the redacted reference-only 500, with the mount's detail kept out of the body.")
    void unansweredRejectionIsRedacted() {
        given()
            .contentType(APPLICATION_JSON)
            .accept(GRAPHQL_RESPONSE_JSON)
            .header("Authorization", AUTHORIZATION)
            .header(EndpointMountedGraphqlResource.REJECT_HEADER, "yes")
            .body(QUERY_TOUCHING_THE_DATABASE)
        .when()
            .post(path("hank"))
        .then()
            .statusCode(500)
            .body(not(containsString("SessionMountException")))
            .body(not(containsString("unentitled principal")));
    }
}
