package no.sikt.graphitron.sakila.example.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Smoke check that the Quarkus + JAX-RS shell boots and serves a GraphQL request end-to-end:
 * POST {@code /graphql} → live Postgres → non-empty response. The only HTTP-shaped test in
 * the module; everything else stays at the schema/engine level.
 */
@QuarkusTest
@QuarkusTestResource(SmokeTestPostgresResource.class)
@ExecutionTier
class GraphqlResourceSmokeTest {

    @Test
    void postReturnsCustomers() {
        var body = given()
            .contentType("application/json")
            .body(Map.of("query", "{ customers { firstName } }"))
            .when()
                .post("/graphql")
            .then()
                .statusCode(200)
                .body("errors", equalTo(null))
            .extract().asString();
        assertThat(body).contains("\"customers\"");
    }
}
