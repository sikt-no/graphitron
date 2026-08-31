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
public class InsertMutationWithoutNodeIdStrategyTest extends ValidationTest {

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER, CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "mutation/insert";
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setUseJdbcBatchingForInserts(false);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setUseJdbcBatchingForInserts(true);
    }

    @Test
    @DisplayName("Generated inserts when node ID strategy is disabled is supported if there is no ID field input")
    void disabledNodeIdStrategyAndNoIdFieldInput() {
        getProcessedSchema("disabledNodeIdStrategyAndNoIdFieldInput");
    }

    @Test
    @DisplayName("Generated inserts when node ID strategy is disabled is not supported if an ID field input has no matching column")
    void disabledNodeIdStrategyAndIdFieldInput() {
        assertErrorsContain("disabledNodeIdStrategyAndIdFieldInput",
                "'Mutation.mutation' is a generated INSERT field with ID input that does not map to any column in table 'CUSTOMER'. " +
                        "Without the node ID strategy such ID fields are resolved with generated extension methods, " +
                        "which this mutation form can not insert into. Fields without a matching column: 'CustomerInputTable.id'."
        );
    }

    @Test
    @DisplayName("Generated inserts when node ID strategy is disabled is supported if the ID field input maps to a column")
    void idFieldMappedToColumn() {
        getProcessedSchema("idFieldMappedToColumn");
    }

    @Test
    @DisplayName("Generated inserts when node ID strategy is disabled is supported if a nested ID field input maps to a column")
    void nestedInputWithMappedIdField() {
        getProcessedSchema("nestedInputWithMappedIdField");
    }

    @Test
    @DisplayName("Generated inserts when node ID strategy is disabled is not supported if a nested ID field input has no matching column")
    void nestedInputWithUnmappedIdField() {
        assertErrorsContain("nestedInputWithUnmappedIdField",
                "'Mutation.mutation' is a generated INSERT field with ID input that does not map to any column in table 'CUSTOMER'.",
                "Fields without a matching column: 'NestedIdInput.id'."
        );
    }

    @Test
    @DisplayName("Give error messages for all mutations with ID input when node ID strategy is disabled")
    void multipleMutationsWithIdInput() {
        assertErrorsContain("multipleMutationsWithIdInput",
                "'Mutation.mutation1'",
                "'Mutation.mutation2'"

        );
    }
}
