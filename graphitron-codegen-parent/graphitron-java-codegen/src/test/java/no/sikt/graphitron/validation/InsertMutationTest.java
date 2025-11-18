package no.sikt.graphitron.validation;

import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Schema validation - Checks run when building the schema for mutations")
public class InsertMutationTest extends ValidationTest {

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_NODE, CUSTOMER_NODE_INPUT_TABLE, VALIDATION_ERROR);
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "mutation/insert";
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
        GeneratorConfig.setUseJdbcBatchingForInserts(false);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
        GeneratorConfig.setUseJdbcBatchingForInserts(true);
    }

    @Test
    @DisplayName("With multiple input records")
    void multipleInputRecords() { // This applies to all generated mutations without JDBC batching, so this test should eventually be moved somewhere more appropriate
        assertErrorsContain("multipleInputRecords",
                "'Mutation.query' is a generated INSERT mutation, but has multiple input records. This is not supported."
        );
    }

    @Test
    @DisplayName("With reference in returned data")
    void withReference() {
        getProcessedSchema("withReference");
    }

    @Test
    @DisplayName("Non-nullable input should not throw error")
    void nonNullableInput() {
        getProcessedSchema("nonNullableInput");
    }

    @Test
    @DisplayName("Non-nullable input list should not throw error")
    void nonNullableInputList() {
        getProcessedSchema("nonNullableInputList");
    }

    @Test
    @DisplayName("Nullable single input not allowed")
    void nullableInput() {
        assertErrorsContain("nullableInput",
                "Field 'Mutation.mutation' is a generated INSERT mutation, but has nullable input. This is not supported. " +
                        "Consider changing the input type from 'CustomerNodeInputTable' to 'CustomerNodeInputTable!'."
        );
    }

    @Test
    @DisplayName("Nullable list with non-null items not allowed")
    void nullableInputList() {
        assertErrorsContain("nullableInputList",
                "Field 'Mutation.mutation' is a generated INSERT mutation, but has nullable input. This is not supported. " +
                        "Consider changing the input type from '[CustomerNodeInputTable!]' to '[CustomerNodeInputTable!]!'."
        );
    }

    @Test
    @DisplayName("Non-nullable list with nullable items not allowed")
    void nonNullableListWithNullableItems() {
        assertErrorsContain("nonNullableListWithNullableItems",
                "Field 'Mutation.mutation' is a generated INSERT mutation, but has nullable input. This is not supported. " +
                        "Consider changing the input type from '[CustomerNodeInputTable]!' to '[CustomerNodeInputTable!]!'."
        );
    }
}
