package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Java record field mapping validation - Checks that fields can be mapped to Java record methods")
public class JavaRecordMappingValidationTest extends ValidationTest {

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "javarecord";
    }

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

    @Test
    @DisplayName("Input with misspelled field should suggest similar setter")
    void inputSuggestion() {
        assertErrorsContain("inputSuggestion",
                "Cannot map field 'emaill' in input 'TestInput' to setter in Java record 'CustomerJavaRecord'. Expected method: setEmaill. Did you mean: email?"
        );
    }

    @Test
    @DisplayName("Output with misspelled field should suggest similar getter")
    void outputSuggestion() {
        assertErrorsContain("outputSuggestion",
                "Cannot map field 'efail' in type 'TestOutput' to getter in Java record 'CustomerJavaRecord'. Expected method: getEfail. Did you mean: email?"
        );
    }

    @Test
    @DisplayName("Nested record with unmapped field should throw error")
    void nestedRecordUnmappedField() {
        assertErrorsContain("nestedRecordUnmappedField",
                "Cannot map field 'invalidField' in input 'InnerInput' to setter in Java record 'MapperCustomerInnerJavaRecord'. Expected method: setInvalidField"
        );
    }
}
