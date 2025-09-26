package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Mutation queries - Queries for updating and then fetching data")
public class FetchQueryTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/edit";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE, CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test  // Works the same for queries, but there were additional null-pointers for mutations.
    @DisplayName("Nested output without table")
    void nestedOutput() {
        assertGeneratedContentContains("nestedOutput",
                "CustomerTable::new",
                "new Outer(internal_it_)",
                ".fetchOne(it -> it.into(Outer.class))",
                " DSL.val(inRecordList.get(internal_it_).getCustomerId())"
        );
    }

    @Test
    @DisplayName("Output after insert")
    void defaultCase() {
        assertGeneratedContentContains("insert",
                ".where(inRecord != null && inRecord.getCustomerId() != null ? _customer.CUSTOMER_ID.eq(inRecord.getCustomerId()) : DSL.falseCondition())");
    }

    @Test
    @DisplayName("Output with composite primary key")
    void tableWithCompositePrimaryKey() {
        assertGeneratedContentContains("tableWithCompositePrimaryKey",
                ".where(inRecord != null && inRecord.getDestinationId() != null ? _vacationdestination.DESTINATION_ID.eq(inRecord.getDestinationId()) : DSL.falseCondition())" +
                        ".and(inRecord != null && inRecord.getCountryName() != null ? _vacationdestination.COUNTRY_NAME.eq(inRecord.getCountryName()) : DSL.falseCondition())"
        );
    }

    @Test
    @DisplayName("Listed output with composite primary key")
    void tableWithCompositePrimaryKeyListed() {
        assertGeneratedContentContains("tableWithCompositePrimaryKeyListed",
                "DSL.row(_vacationdestination.DESTINATION_ID, _vacationdestination.COUNTRY_NAME)",
                        "DSL.val(inRecordList.get(internal_it_).getDestinationId())"
        );
    }

    @Test
    @DisplayName("Don't filter on non-PK fields in jOOQ record input when fetching after mutation")
    void inputRecordWithNonPkField() {
        assertGeneratedContentContains("inputRecordWithNonPkField",
                ".where(inRecord != null && inRecord.getCustomerId() != null ? _customer.CUSTOMER_ID.eq(inRecord.getCustomerId()) : DSL.falseCondition())" +
                        ".fetch"
        );
    }
}
