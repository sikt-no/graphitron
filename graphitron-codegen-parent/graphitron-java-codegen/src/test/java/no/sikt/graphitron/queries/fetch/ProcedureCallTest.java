package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_QUERY;

@DisplayName("Query outputs - @experimental_procedureCall directive")
public class ProcedureCallTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/procedureCall";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Procedure call with a single column-sourced argument")
    void procedureCall() {
        assertGeneratedContentContains("procedureCall", Set.of(CUSTOMER_QUERY),
                "Routines.inventoryHeldByCustomer(_a_customer.CUSTOMER_ID)"
        );
    }

    @Test
    @DisplayName("Procedure call with multiple arguments in jOOQ declaration order")
    void procedureCallMultipleArgs() {
        assertGeneratedContentContains("procedureCallMultipleArgs", Set.of(CUSTOMER_QUERY),
                "Routines.getCustomerBalance(_a_customer.CUSTOMER_ID, _a_customer.LAST_UPDATE)"
        );
    }

    @Test
    @DisplayName("Procedure call with schema-qualified routine name")
    void procedureCallQualifiedRoutine() {
        assertGeneratedContentContains("procedureCallQualifiedRoutine", Set.of(CUSTOMER_QUERY),
                "Routines.lastDay(_a_customer.LAST_UPDATE)"
        );
    }

    @Test
    @DisplayName("Procedure call on a root field")
    void procedureCallTargetOnRoot() {
        assertGeneratedContentContains("procedureCallTargetOnRoot",
                "Routines.inventoryHeldByCustomer(_mi_inventoryId)"
        );
    }

    @Test
    @DisplayName("Procedure call on a non-root data-fetcher")
    void procedureCallTargetOnArgField() {
        assertGeneratedContentContains("procedureCallTargetOnArgField",
                "Routines.inventoryHeldByCustomer(_mi_inventoryId)"
        );
    }

    @Test
    @DisplayName("Procedure call on a @splitQuery field")
    void procedureCallTargetSplitQuery() {
        assertGeneratedContentContains("procedureCallTargetSplitQuery",
                "Routines.inventoryHeldByCustomer(_mi_inventoryId)"
        );
    }
}
