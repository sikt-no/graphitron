package no.sikt.graphitron.validation;

import no.sikt.graphitron.common.configuration.SchemaComponent;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @Test
    @DisplayName("With node ID as unique identifier")
    void nodeId() {
        getProcessedSchema("nodeId");
        assertNoWarnings();
    }

    @Test
    @DisplayName("With ID as unique identifier")
    void id() { // Temporary test until node ID strategy is required
        getProcessedSchema("id", Set.of(CUSTOMER_INPUT_TABLE));
        assertNoWarnings();
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
                "Mutation field 'Mutation.mutation' is a generated delete mutation, but does not have input with non-nullable fields corresponding to a PK/UK of the table."
        );
    }

    @Test
    @DisplayName("Has fields matching PK, but not all are non-nullable")
    void onePrimaryKeyFieldNullable() {
        assertErrorsContain(
                "onePrimaryKeyFieldNullable",
                "Mutation field 'Mutation.mutation' is a generated delete mutation, but does not have input with non-nullable fields corresponding to a PK/UK of the table."
        );
    }

    @Test
    @DisplayName("Nullable node ID")
    void nullableNodeId() {
        assertErrorsContain(
                "nullableNodeId",
                "Mutation field 'Mutation.mutation' is a generated delete mutation, but does not have input with non-nullable fields corresponding to a PK/UK of the table."
        );
    }

    @Test
    @DisplayName("Without jOOQ record input")
    void withoutJooqRecordInput() {
        assertErrorsContain(
                "withoutJooqRecordInput",
                "Mutation field 'Mutation.mutation' is a generated delete mutation, but does not link any input to tables."
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
    @DisplayName("No data fields should throw error")
    void noDataFields() {
        assertErrorsContain(
                "noDataFields", Set.of(VALIDATION_ERROR),
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
}
