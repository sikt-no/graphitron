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

@DisplayName("Mutation queries - Queries for updating with JDBC batching and then fetching data")
public class FetchQueryTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/edit/withBatching";
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
                "new Outer(_iv_it)",
                ".fetchOne(_iv_it -> _iv_it.into(Outer.class))",
                " DSL.val(_mi_inRecordList.get(_iv_it).getCustomerId())"
        );
    }

    @Test
    @DisplayName("Output after insert")
    void defaultCase() {
        assertGeneratedContentContains("insert",
                ".where(_mi_inRecord != null && _mi_inRecord.getCustomerId() != null ? _a_customer.CUSTOMER_ID.eq(_mi_inRecord.getCustomerId()) : DSL.falseCondition())"
        );
    }

    @Test
    @DisplayName("Output with composite primary key")
    void tableWithCompositePrimaryKey() {
        assertGeneratedContentContains("tableWithCompositePrimaryKey",
                ".where(_mi_inRecord != null && _mi_inRecord.getDestinationId() != null ? _a_vacationdestination.DESTINATION_ID.eq(_mi_inRecord.getDestinationId()) : DSL.falseCondition())" +
                        ".and(_mi_inRecord != null && _mi_inRecord.getCountryName() != null ? _a_vacationdestination.COUNTRY_NAME.eq(_mi_inRecord.getCountryName())"
        );
    }

    @Test
    @DisplayName("Listed output with composite primary key")
    void tableWithCompositePrimaryKeyListed() {
        assertGeneratedContentContains("tableWithCompositePrimaryKeyListed",
                "DSL.row(_a_vacationdestination.DESTINATION_ID, _a_vacationdestination.COUNTRY_NAME)",
                "DSL.val(_mi_inRecordList.get(_iv_it).getDestinationId())"
        );
    }

    @Test
    @DisplayName("Don't filter on non-PK fields in jOOQ record input when fetching after mutation")
    void inputRecordWithNonPkField() {
        assertGeneratedContentContains("inputRecordWithNonPkField",
                ".where(_mi_inRecord != null && _mi_inRecord.getCustomerId() != null ? _a_customer.CUSTOMER_ID.eq(_mi_inRecord.getCustomerId()) : DSL.falseCondition()).fetch"
        );
    }
}
