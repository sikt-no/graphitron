package no.sikt.graphitron.sakila.example.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import no.sikt.graphitron.jakarta.rest.GraphitronApplication;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;

/**
 * {@code GET /graphql/schema} serves the adapter's {@link GraphitronApplication#schemaSdl()}, the
 * facade's print, rather than a library-side print of the schema. {@link AdapterSchemaSdlTest}
 * holds what that print contains for a federated subgraph; this class ties the HTTP route to it.
 */
@QuarkusTest
@QuarkusTestResource(SmokeTestPostgresResource.class)
@ResourceLock(QuarkusTestLock.KEY)
@ExecutionTier
class SchemaEndpointTest {

    @Inject
    GraphitronApplication application;

    @Test
    @DisplayName("The built-in SDL endpoint serves the adapter's schemaSdl as text/plain.")
    void schemaEndpointServesTheAdapterSdl() {
        var body = given()
        .when()
            .get("/graphql/schema")
        .then()
            .statusCode(200)
            .contentType(startsWith("text/plain"))
        .extract().asString();

        assertThat(body).isEqualTo(application.schemaSdl());
        // graphql-java's default printer leaves this block out for a schema with standard root
        // type names, which is the print the endpoint used to serve.
        assertThat(body).contains("schema {");
    }
}
