package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Query outputs - With optional select enabled for external fields only")
public class OptionalSelectExternalFieldsOnlyTest extends OptionalSelectPartiallyEnabledTest {
    @BeforeAll
    static void setUp() {
        GeneratorConfig.setOptionalSelectOnExternalFields(true);
        GeneratorConfig.setOptionalSelectOnSubqueries(false);
    }

    @Test
    @DisplayName("External fields should be wrapped with ifRequested")
    void externalFieldIsWrapped() {
        assertGeneratedContentContains(
                "externalField",
                "_iv_select.ifRequested(\"name\", () -> no.sikt.graphitron.codereferences.extensionmethods.ClassWithExtensionMethod.name(_a_customer))"
        );
    }

    @Test
    @DisplayName("Subqueries should NOT be wrapped with ifRequested when onSubqueryReferences is disabled")
    void subqueryIsNotWrapped() {
        resultDoesNotContain(
                "fieldReference",
                "ifRequested"
        );
    }
}
