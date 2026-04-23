package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.sikt.graphql.directives.GenerationDirective.RECORD;
import static no.sikt.graphql.directives.GenerationDirective.TABLE;

@DisplayName("Input directive validation - Checks for conflicting directives on input types")
public class InputDirectiveValidationTest extends ValidationTest {

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "inputDirective";
    }

    @Test
    @DisplayName("Input with both @table and @record should log warning")
    void tableAndRecord() {
        getProcessedSchema("tableAndRecord");
        assertWarningsContain(
                String.format(
                        "Input types can only be mapped to one record category (either a jOOQ record via @%1$s or a " +
                                "Java record via @%2$s), but input type 'TestInput' has both directives. Remove @%1$s to " +
                                "preserve the existing behaviour.",
                        TABLE.getName(), RECORD.getName()
                )
        );
    }

    @Test
    @DisplayName("Input with only @table is valid")
    void tableOnly() {
        getProcessedSchema("tableOnly");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Input with only @record is valid")
    void recordOnly() {
        getProcessedSchema("recordOnly");
        assertNoWarnings();
    }
}