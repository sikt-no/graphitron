package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Query outputs - With optional select enabled for subqueries only")
public class OptionalSelectSubqueriesOnlyTest extends OptionalSelectPartiallyEnabledTest {
    @BeforeAll
    static void setUp() {
        GeneratorConfig.setOptionalSelectOnExternalFields(false);
        GeneratorConfig.setOptionalSelectOnSubqueries(true);
    }

    @Test
    @DisplayName("Subqueries should be wrapped with ifRequested")
    void subqueryIsWrapped() {
        assertGeneratedContentContains(
                "fieldReference",
                "_iv_select.ifRequested(\"address\", () ->"
        );
    }

    @Test
    @DisplayName("External fields should NOT be wrapped with ifRequested when onExternalFields is disabled")
    void externalFieldIsNotWrapped() {
        resultDoesNotContain(
                "externalField",
                "ifRequested"
        );
    }
}