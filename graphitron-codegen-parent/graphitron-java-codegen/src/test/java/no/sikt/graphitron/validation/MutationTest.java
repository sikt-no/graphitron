package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.ERROR;

@DisplayName("Schema validation - Checks run when building the schema for mutations")
public class MutationTest extends ValidationTest { // TODO: Some of these tests can be generalised, as they also apply to queries.
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

    @Test
    @DisplayName("Input that has no records")
    void noRecordInput() {
        assertErrorsContain("noRecordInput",
                "Problems have been found that prevent code generation:",
                        "Mutations must have at least one table attached when generating resolvers with queries. Mutation 'mutation' has no tables attached."
        );
    }

    @Test  // Not sure if this is the intended behaviour for such cases. The mutation does not try to unwrap the input.
    @DisplayName("Input with non-record wrapper containing a record")
    void wrappedInput() {
        assertErrorsContain("wrappedInput", Set.of(CUSTOMER_INPUT_TABLE),
                "Problems have been found that prevent code generation:",
                        "Mutations must have at least one table attached when generating resolvers with queries. Mutation 'mutation' has no tables attached."
        );
    }

    @Test
    @DisplayName("Input table is required but unresolvable")
    void multipleInputRecords() {
        assertErrorsContain(
                "multipleInputRecords", Set.of(CUSTOMER_INPUT_TABLE),
                "Query.query is a field of a type without a table, and has 2 potential input records to use as a source for the table in queries. In such cases, there must be exactly one input table so that it can be resolved unambiguously."
        );
    }

    @Test
    @DisplayName("Neither service nor mutation directive is set on a mutation")
    void noHandlingSet() {
        assertErrorsContain("noHandlingSet", "Mutation 'mutation' is set to generate, but has neither a service nor mutation type set.");
    }

    @Test
    @DisplayName("Circular reference between input types without @table should report the cycle")
    void circularInputReferenceWithoutTable() {
        assertErrorsContain("circularInputReferenceWithoutTable",
                "Circular reference detected without @table directive: InputA -> InputB -> InputA. Add a @table directive to one of these types to break the cycle.");
    }

    @Test
    @DisplayName("Self-referencing input type without @table should report the cycle")
    void selfReferencingInputWithoutTable() {
        assertErrorsContain("selfReferencingInputWithoutTable",
                "Circular reference detected without @table directive: InputA -> InputA. Add a @table directive to one of these types to break the cycle.");
    }
}
