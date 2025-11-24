package no.sikt.graphitron.validation;

import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.*;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Schema validation - Checks run when building the schema for mutations")
public class DeleteMutationTest extends ValidationTest {

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_NODE, CUSTOMER_NODE_INPUT_TABLE, VALIDATION_ERROR);
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "mutation/delete";
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
        GeneratorConfig.setUseJdbcBatchingForDeletes(false);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
        GeneratorConfig.setUseJdbcBatchingForDeletes(true);
    }

    @Test
    @DisplayName("With node ID as unique identifier")
    void nodeId() {
        getProcessedSchema("nodeId");
        assertNoWarnings();
    }

    @Test
    @DisplayName("With ID as unique identifier")
    void id() { // Temporary test until node ID strategy is required
        GeneratorConfig.setNodeStrategy(false);
        getProcessedSchema("id", Set.of(CUSTOMER_INPUT_TABLE));
        assertNoWarnings();
        GeneratorConfig.setNodeStrategy(true);
    }

    @Test
    @DisplayName("With primary key fields as unique identifier")
    void primaryKeyFields() {
        getProcessedSchema("primaryKeyFields");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Missing one field to match primary key of table")
    void missingFieldToMatchPrimaryKey() {
        assertErrorsContain(
                "missingFieldToMatchPrimaryKey",
                "Mutation field 'Mutation.mutation' is a generated delete mutation, but does not have input with non-nullable fields corresponding to a PK/UK of the table.",
                "Possible fix(es):\n* Add non-nullable input fields for COUNTRY_NAME to match PK/UK 'vacation_destination_pkey'."
        );
    }

    @Test
    @DisplayName("Missing multiple fields to match primary key of table")
    void missingMultipleFieldsToMatchPrimaryKey() {
        assertErrorsContain(
                "missingMultipleFieldsToMatchPrimaryKey",
                "Mutation field 'Mutation.mutation' is a generated delete mutation, but does not have input with non-nullable fields corresponding to a PK/UK of the table.",
                "Possible fix(es):\n* Add non-nullable input fields for COUNTRY_NAME, DESTINATION_ID to match PK/UK 'vacation_destination_pkey'."
        );
    }

    @Test
    @DisplayName("Has fields matching PK, but not all are non-nullable")
    void onePrimaryKeyFieldNullable() {
        assertErrorsContain(
                "onePrimaryKeyFieldNullable",
                "Mutation field 'Mutation.mutation' is a generated delete mutation, but does not have input with non-nullable fields corresponding to a PK/UK of the table.",
                "Possible fix(es):\n* Add non-nullable input fields for DESTINATION_ID to match PK/UK 'vacation_destination_pkey'."
        );
    }

    @Test
    @DisplayName("Nullable node ID")
    void nullableNodeId() {
        assertErrorsContain(
                "nullableNodeId",
                "Mutation field 'Mutation.mutation' is a generated delete mutation, but does not have input with non-nullable fields corresponding to a PK/UK of the table.",
                """
                        Possible fix(es):
                        * Make 'CustomerInput.id' non-nullable.
                        * Add non-nullable node ID input field for type 'CustomerNode'.
                        * Add non-nullable input fields for CUSTOMER_ID to match PK/UK 'customer_pkey'."""
        );
    }

    @Test
    @DisplayName("Reference node ID should not be mistaken for identifying a row in target table")
    void referenceNodeId() {
        assertErrorsContain(
                "referenceNodeId",
                "Mutation field 'Mutation.mutation' is a generated delete mutation, but does not have input with non-nullable fields corresponding to a PK/UK of the table.",
                "Possible fix(es):\n* Add non-nullable node ID input field for type 'CustomerNode'.\n* Add non-nullable input fields for CUSTOMER_ID to match PK/UK 'customer_pkey'."
        );
    }

    @Test
    @DisplayName("Reference ID should not be mistaken for identifying a row in target table")
    void referenceId() { // Temporary test until node ID strategy is required
        GeneratorConfig.setNodeStrategy(false);
        assertErrorsContain(
                "referenceId",
                "Mutation field 'Mutation.mutation' is a generated delete mutation, but does not have input with non-nullable fields corresponding to a PK/UK of the table.",
                "Possible fix(es):\n* Add non-nullable node ID input field for type 'CustomerNode'.\n* Add non-nullable input fields for CUSTOMER_ID to match PK/UK 'customer_pkey'."
        );
        GeneratorConfig.setNodeStrategy(true);
    }

    @Test
    @DisplayName("Without jOOQ record input")
    void withoutJooqRecordInput() {
        assertErrorsContain(
                "withoutJooqRecordInput",
                "Field 'Mutation.mutation' is a generated DELETE mutation, but does not link any input to tables."
        );
    }


    /* Output data */

    @Test
    @DisplayName("Returning scalar data")
    void returningScalar() {
        getProcessedSchema("returningScalar");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Returning wrapped scalar data")
    void returningWrappedScalar() {
        getProcessedSchema("returningWrappedScalar");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Returning table object")
    void returningTableObject() {
        getProcessedSchema("returningTableObject");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Returning wrapped table object")
    void returningWrappedTableObject() {
        getProcessedSchema("returningWrappedTableObject");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Multiple possible data fields should throw error")
    void multipleDataFields() {
        assertErrorsContain(
                "multipleDataFields",
                "Cannot find correct field to output data to after mutation for field 'Mutation.mutation'."
        );
    }

    @Test
    @DisplayName("Only errors field (and no data field) should throw error")
    void onlyErrorsField() {
        assertErrorsContain(
                "onlyErrorsField", Set.of(VALIDATION_ERROR),
                "Cannot find correct field to output data to after mutation for field 'Mutation.mutation'."
        );
    }

    @Test
    @DisplayName("Only error/exception union field (with non-reserved name) and no data field should throw error")
    void onlyErrorExceptionUnionField() {
        assertErrorsContain(
                "onlyErrorExceptionUnionField", Set.of(VALIDATION_ERROR),
                "Cannot find correct field to output data to after mutation for field 'Mutation.mutation'."
        );
    }

    @Test
    @DisplayName("Throw error on mismatch between input and ouput tables")
    void mismatchBetweenInputAndOutputTables() {
        assertErrorsContain(
                "mismatchBetweenInputAndOutputTables", Set.of(VALIDATION_ERROR),
                "Mutation field 'Mutation.mutation' has a mismatch between input and output tables. " +
                        "Input table is 'CUSTOMER', and output table is 'VACATION_DESTINATION'."
        );
    }

    @Test
    @DisplayName("With reference in returned data")
    void withReference() {
        assertErrorsContain(
                "withReference",
                "Mutation field 'Mutation.mutation' has references returned from the data field. This is not supported for DELETE mutations. Found reference fields are: 'Customer.address'"
        );
    }

    @Test
    @DisplayName("With nested reference in returned data")
    void withNestedReference() {
        assertErrorsContain(
                "withNestedReference",
                "Mutation field 'Mutation.mutation' has references returned from the data field. This is not supported for DELETE mutations. Found reference fields are: 'AddressWrapper.address'"
        );
    }

    @Test
    @DisplayName("With reference node ID in returned data")
    void withNodeIdReference() {
        assertErrorsContain(
                "withNodeIdReference",
                "Found reference fields are: 'Customer.addressId'"
        );
    }

    @Test
    @DisplayName("With wrapped reference node ID in returned data")
    void withWrappedNodeIdReference() {
        assertErrorsContain(
                "withWrappedNodeIdReference",
                "Found reference fields are: 'AddressWrapper.addressId'"
        );
    }

    @Test
    @DisplayName("Nullable single input not allowed")
    void nullableInput() {
        assertErrorsContain("nullableInput",
                // This is the same logic as for insert mutations, and more thoroughly tested in no.sikt.graphitron.validation.InsertMutationTest
                "Field 'Mutation.mutation' is a generated DELETE mutation, but has nullable input. This is not supported."
        );
    }
}
