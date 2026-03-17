package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("OrderBy input validation - Checks that @orderBy input types have valid structure")
public class OrderByInputValidationTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "orderByInput/";
    }

    @Test
    @DisplayName("Input type without orderBy enum field should fail validation")
    void missingOrderByEnum() {
        assertErrorsContain("missingOrderByEnum", Set.of(CUSTOMER_TABLE, ORDER_DIRECTION),
                "Expected exactly one orderBy enum field on type");
    }

    @Test
    @DisplayName("Input type without direction enum field should fail validation")
    void missingDirectionEnum() {
        assertErrorsContain("missingDirectionEnum", Set.of(CUSTOMER_TABLE),
                "Expected exactly one direction enum field on type");
    }

    @Test
    @DisplayName("Enum without @order directives should fail validation as missing orderBy field")
    void missingOrderDirective() {
        assertErrorsContain("missingOrderDirective", Set.of(CUSTOMER_TABLE, ORDER),
                "Expected exactly one orderBy enum field on type");
    }
}
