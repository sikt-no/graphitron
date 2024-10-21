package no.fellesstudentsystem.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.ERROR;

@DisplayName("Schema validation - Checks run when building the schema for mutations")
public class MutationTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "mutation";
    }

    @Test
    @DisplayName("Insert mutation without records")
    void insertWithoutRecords() {
        assertErrorsContain(
                "insertWithoutRecords",
                "Mutation mutation is set as an insert operation, but does not link any input to tables."
        );
    }

    @Test
    @DisplayName("Iterable input inside another input")
    void iterableNestedInputField() {
        assertErrorsContain("iterableNestedInputField",
                "Field wrapper with Input type Wrapper is iterable, but has no record mapping set. " +
                        "Iterable Input types within records without record mapping can not be mapped to a single field in the surrounding record."
        );
    }

    @Test  // Note this also prints that the field is not set as required, though that much should be obvious just from this message.
    @DisplayName("Missing required insert field")
    void missingInsertField() {
        getProcessedSchema("missingInsertField");
        assertWarningsContain(
                "Input type Customer referencing table CUSTOMER does not map all fields required by the database. " +
                        "Missing required fields: FIRST_NAME"
        );
    }

    @Test
    @DisplayName("Insert field that should be required is not")
    void missingInsertRequiredField() {
        getProcessedSchema("missingInsertRequiredField");
        assertWarningsContain(
                "Input type Customer referencing table CUSTOMER does not map all fields required by the database as non-nullable. " +
                        "Nullable required fields: FIRST_NAME"
        );
    }

    @Test
    @DisplayName("Missing required insert field that has a default set")
    void missingInsertFieldWithDefault() {
        getProcessedSchema("missingInsertFieldWithDefault");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Only input is iterable")
    void onlyInputIterable() {
        getProcessedSchema("onlyInputIterable", CUSTOMER_INPUT_TABLE);
        assertWarningsContain("Mutation mutation with Input CustomerInputTable is defined as a list while Payload type Response does not contain a list");
    }

    @Test
    @DisplayName("Only input is iterable when output includes errors")
    void onlyInputIterableWithErrors() {
        getProcessedSchema("onlyInputIterableWithErrors", CUSTOMER_INPUT_TABLE, ERROR);
        assertWarningsContain("CustomerInputTable is defined as a list", "Response does not contain");
    }

    @Test
    @DisplayName("Only output is iterable")
    void onlyOutputIterable() {
        getProcessedSchema("onlyOutputIterable", CUSTOMER_INPUT_TABLE);
        assertWarningsContain("Mutation mutation with Input CustomerInputTable is not defined as a list while Payload type Response contains a list");
    }

    @Test
    @DisplayName("Only an output field is iterable")
    void onlyOutputFieldIterable() {
        getProcessedSchema("onlyOutputFieldIterable", CUSTOMER_INPUT_TABLE);
        assertWarningsContain("CustomerInputTable is not defined as a list", "Response contains a list");
    }

    @Test
    @DisplayName("Only an output field is iterable with an extra non iterated output field")
    void onlyOutputFieldIterableWithExtraField() {
        getProcessedSchema("onlyOutputFieldIterableWithExtraField", CUSTOMER_INPUT_TABLE);
        assertWarningsContain("CustomerInputTable is not defined as a list", "Response contains a list");
    }

    @Test
    @DisplayName("Both input and output are iterable")
    void bothIterable() {
        getProcessedSchema("bothIterable", CUSTOMER_INPUT_TABLE);
        assertNoWarnings();
    }

    @Test
    @DisplayName("Both input and at least one output field in a mutation are iterable")
    void bothOuterIterable() {
        getProcessedSchema("bothOuterIterable", CUSTOMER_INPUT_TABLE);
        assertNoWarnings();
    }

    @Test
    @DisplayName("Both input and output are iterable and output and output contains an extra not iterable field")
    void bothIterableWithExtraField() {
        getProcessedSchema("bothIterableWithExtraField", CUSTOMER_INPUT_TABLE);
        assertNoWarnings();
    }

    @Test
    @DisplayName("Both input and output are not iterable")
    void bothNotIterable() {
        getProcessedSchema("bothNotIterable", CUSTOMER_INPUT_TABLE);
        assertNoWarnings();
    }

    @Test
    @DisplayName("Both input and output are not iterable and output contains errors")
    void bothNotIterableWithErrors() {
        getProcessedSchema("bothNotIterableWithErrors", CUSTOMER_INPUT_TABLE, ERROR);
        assertNoWarnings();
    }
}
