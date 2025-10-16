package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.PAYMENT_CONDITION;

@DisplayName("Helper method generation and naming")
public class HelperMethodTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/helperMethods";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(PAYMENT_CONDITION);
    }

    @Test
    @DisplayName("Multiple nested fields accessing same leaf type generate unique helper method names")
    void duplicateNestedFields() {
        // This test verifies that unique helper method names are generated when a helper method has multiple wrapper fields
        // that both lead to the same nested type (StoreInfo.primaryStaff.staff and StoreInfo.secondaryStaff.staff)
        assertGeneratedContentContains(
                "duplicateNestedFields",
                // Helper method for storeInfo at depth 1 (nested under customer)
                "private static SelectField<StoreInfo> queryForQuery_customer_d1_storeInfo(",
                "DSL.select(queryForQuery_customer_d1_storeInfo())",
                // First helper for staff at depth 2 (nested under storeInfo, no counter suffix since it's the first)
                "private static SelectField<Staff> queryForQuery_customer_d1_storeInfo_d2_staff(",
                // Second helper for staff at depth 2 (with _1 counter suffix for uniqueness)
                "private static SelectField<Staff> queryForQuery_customer_d1_storeInfo_d2_staff_1(",
                // Both should be called from the storeInfo helper
                "DSL.select(queryForQuery_customer_d1_storeInfo_d2_staff()",
                "DSL.select(queryForQuery_customer_d1_storeInfo_d2_staff_1()"
        );
    }

    @Test
    @DisplayName("Fields with underscore names that could collide with nested paths")
    void potentialCollision() {
        assertGeneratedContentContains(
                "potentialCollision",
                // Main rental() helper calls nested helpers with depth indicators
                "DSL.select(rentalForQuery_rental_d1_staff())",
                "DSL.select(rentalForQuery_rental_d1_staff_store())",
                // staff at depth 1 (nested under rental)
                "private static SelectField<Staff> rentalForQuery_rental_d1_staff(",
                // staff_store at depth 1 - distinct from staff.store due to depth nesting
                "private static SelectField<Store> rentalForQuery_rental_d1_staff_store(",
                // staff.store nested at depth 2 (staff at depth 1, store at depth 2) - distinct from staff_store
                "private static SelectField<Store> rentalForQuery_rental_d1_staff_d2_store("
        );
    }

    @Test
    @DisplayName("Helper methods with correlated WHERE clauses should not reference undefined parent aliases")
    void correlatedSubqueryReferencesPossibleOutput() {
        assertGeneratedContentMatches("correlatedSubqueryReferences");
    }
}
