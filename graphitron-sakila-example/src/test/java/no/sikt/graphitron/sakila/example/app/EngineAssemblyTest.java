package no.sikt.graphitron.sakila.example.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;

/**
 * The engine this module's adapter assembles through the generated runtime, with an application
 * instrumentation chained after graphitron's: connection handling still works (a database read on
 * the pinned connection succeeds, with the jOOQ {@code Settings} the runtime was given), and the
 * extra instrumentation ran for the operation, after graphitron's had set its carrier up. The test
 * adapter ({@link FaultInjectingGraphitronApplication}) installs {@link RecordingInstrumentation}
 * for every test in the module, so this pins what they all run on.
 */
@QuarkusTest
@QuarkusTestResource(SmokeTestPostgresResource.class)
@ResourceLock(QuarkusTestLock.KEY)
@ExecutionTier
class EngineAssemblyTest {

    @Test
    @DisplayName("An extra instrumentation chained through the runtime runs after graphitron's, and the operation still reads through the pinned connection.")
    void extraInstrumentationRunsAfterGraphitronsAndConnectionHandlingWorks() {
        int before = RecordingInstrumentation.INSTANCE.operations.get();
        int carrierBefore = RecordingInstrumentation.INSTANCE.operationsWithGraphitronsCarrierAlreadyPresent.get();

        given()
            .contentType("application/json")
            .accept("application/graphql-response+json")
        .when()
            .body("{\"query\":\"{ filmsOrdered { title } }\"}")
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.filmsOrdered.size()", greaterThan(0));

        assertThat(RecordingInstrumentation.INSTANCE.operations.get()).isEqualTo(before + 1);
        assertThat(RecordingInstrumentation.INSTANCE.operationsWithGraphitronsCarrierAlreadyPresent.get())
            .isEqualTo(carrierBefore + 1);
    }
}
