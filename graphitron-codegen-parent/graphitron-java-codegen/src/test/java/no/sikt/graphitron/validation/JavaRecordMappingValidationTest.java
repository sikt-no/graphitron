package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Java record field mapping validation - Checks that fields can be mapped to Java record methods")
public class JavaRecordMappingValidationTest extends ValidationTest {

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "javarecord";
    }

    // Input type tests (setter validation)

    @Test
    @DisplayName("Valid input: basic field, @field with javaName, @notGenerated (skipped), flattened nested input, nested @table input (skipped)")
    void validMappings() {
        getProcessedSchema("inputValidMapping");
    }

    @Test
    @DisplayName("Input with unmapped field should throw error")
    void unmappedField() {
        assertErrorsContain("inputUnmappedField",
                "Cannot map field 'invalidField' in input 'TestInput' to setter in Java record 'CustomerJavaRecord'. Expected method: setInvalidField"
        );
    }

    @Test
    @DisplayName("Flattened nested input with unmapped field should throw error")
    void nestedUnmappedField() {
        assertErrorsContain("inputNestedUnmappedField",
                "Cannot map field 'invalidNestedField' in input 'NestedInput' to setter in Java record 'CustomerJavaRecord'. Expected method: setInvalidNestedField"
        );
    }

    @Test
    @DisplayName("Unmapped input field should produce warning instead of error when failOnJavaRecordMappingErrors=false")
    void unmappedFieldAsWarning() {
        GeneratorConfig.setFailOnJavaRecordMappingErrors(false);
        try {
            getProcessedSchema("inputUnmappedField");
            assertWarningsContain(
                    "Cannot map field 'invalidField' in input 'TestInput' to setter in Java record 'CustomerJavaRecord'. Expected method: setInvalidField"
            );
        } finally {
            GeneratorConfig.setFailOnJavaRecordMappingErrors(true);
        }
    }

    // Output type tests (getter validation)

    @Test
    @DisplayName("Valid output: basic field, @field with javaName, @notGenerated (skipped), flattened nested type")
    void validOutputMappings() {
        getProcessedSchema("outputValidMapping");
    }

    @Test
    @DisplayName("Output type with unmapped field should throw error")
    void unmappedOutputField() {
        assertErrorsContain("outputUnmappedField",
                "Cannot map field 'invalidField' in type 'TestOutput' to getter in Java record 'CustomerJavaRecord'. Expected method: getInvalidField"
        );
    }
}
