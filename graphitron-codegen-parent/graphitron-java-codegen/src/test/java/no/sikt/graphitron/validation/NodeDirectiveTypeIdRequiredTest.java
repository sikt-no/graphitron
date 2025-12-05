package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Node directive typeId required validation - Checks that typeId is required when configured")
public class NodeDirectiveTypeIdRequiredTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "nodeDirective/typeIdRequired";
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setRequireTypeIdOnNode(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setRequireTypeIdOnNode(false);
    }

    @Test
    @DisplayName("Node directive with typeId provided should not throw error")
    void withTypeId() {
        getProcessedSchema("withTypeId");
    }

    @Test
    @DisplayName("Node directive missing typeId when required")
    void missingTypeId() {
        assertErrorsContain("missingTypeId",
                "Type 'Customer' has the 'node' directive, but is missing the 'typeId' parameter which has been configured to be required."
        );
    }
}