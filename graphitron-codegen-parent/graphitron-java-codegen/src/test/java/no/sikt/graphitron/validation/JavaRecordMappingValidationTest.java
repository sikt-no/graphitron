package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Java record field mapping validation - Checks that input fields can be mapped to Java record setters")
public class JavaRecordMappingValidationTest extends ValidationTest {

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "javarecord";
    }

    @Test
    @DisplayName("Valid mappings: basic field, @field with javaName, @notGenerated, flattened nested input, nested @table input (skipped)")
    void validMappings() {
        getProcessedSchema("validMapping");
    }

    @Test
    @DisplayName("Input with unmapped field should throw error")
    void unmappedField() {
        assertErrorsContain("unmappedField",
                "Cannot map field 'invalidField' in input 'TestInput' to setter in Java record 'CustomerJavaRecord'. Expected method: setInvalidField"
        );
    }

    @Test
    @DisplayName("Flattened nested input with unmapped field should throw error")
    void nestedUnmappedField() {
        assertErrorsContain("nestedUnmappedField",
                "Cannot map field 'invalidNestedField' in input 'NestedInput' to setter in Java record 'CustomerJavaRecord'. Expected method: setInvalidNestedField"
        );
    }
}
