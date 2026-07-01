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
 * POST {@code /graphql} → live Postgres → non-empty response, plus the self-hosted GraphiQL
 * playground page and its asset endpoint. The only HTTP-shaped tests in the module; everything
 * else stays at the schema/engine level.
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

    @Test
    void graphiqlPageIsSelfHostedWithResolvedAssetBase() {
        var page = given()
            .accept("text/html")
            .when()
                .get("/graphql")
            .then()
                .statusCode(200)
            .extract().asString();
        // The GraphiQL SPA mount point is present.
        assertThat(page).contains("id=\"graphiql\"");
        // No runtime CDN dependency: the whole point of self-hosting.
        assertThat(page).doesNotContain("unpkg.com");
        // The {{ASSET_BASE}} placeholder is rewritten to the absolute assets prefix, so the entry
        // files resolve wherever the resource is mounted.
        assertThat(page).doesNotContain("{{ASSET_BASE}}");
        assertThat(page).contains("/graphql/assets/graphiql.js");
        assertThat(page).contains("/graphql/assets/graphiql.css");
    }

    @Test
    void graphiqlEntryAssetIsServedWithJsMediaType() {
        var body = given()
            .when()
                .get("/graphql/assets/graphiql.js")
            .then()
                .statusCode(200)
                .contentType("text/javascript")
            .extract().asByteArray();
        assertThat(body).isNotEmpty();
    }

    @Test
    void graphiqlCssAssetIsServed() {
        given()
            .when()
                .get("/graphql/assets/graphiql.css")
            .then()
                .statusCode(200)
                .contentType("text/css");
    }

    @Test
    void missingAssetIsNotFound() {
        given()
            .when()
                .get("/graphql/assets/nope.js")
            .then()
                .statusCode(404);
    }

    @Test
    void unknownExtensionAssetIsNotFound() {
        given()
            .when()
                .get("/graphql/assets/graphiql.txt")
            .then()
                .statusCode(404);
    }
}
