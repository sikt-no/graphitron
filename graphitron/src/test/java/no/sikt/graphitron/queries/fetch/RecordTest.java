package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_RECORD;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Fetch queries - Queries using input records")
public class RecordTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/records";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_RECORD);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Input Java records")
    void inputJavaRecord() {
        assertGeneratedContentContains(
                "inputJavaRecord",
                "customerForQuery(DSLContext ctx, DummyRecord inRecord,",
                "inRecord != null ? _customer.hasId(inRecord.getId()) : DSL.noCondition()",
                "inRecord != null ? _customer.hasId(inRecord.getOtherID()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Listed input Java records")
    void listedInputJavaRecord() {
        assertGeneratedContentContains(
                "listedInputJavaRecord",
                "customerForQuery(DSLContext ctx, List<DummyRecord> inRecordList,",
                "DSL.row(_customer.getId(), _customer.getId()).in(" +
                        "    inRecordList.stream().map(internal_it_ -> DSL.row(DSL.inline(internal_it_.getId()), DSL.inline(internal_it_.getOtherID()))).collect(Collectors.toList())" +
                        ") : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Input jOOQ records")
    void inputJOOQRecord() {
        assertGeneratedContentContains(
                "inputJOOQRecord",
                "customerForQuery(DSLContext ctx, CustomerRecord inRecord,",
                "inRecord != null ? _customer.hasId(inRecord.getId()) : DSL.noCondition()",
                "inRecord != null ? _customer.FIRST_NAME.eq(inRecord.getFirstName()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Listed input jOOQ records")
    void listedInputJOOQRecord() {
        assertGeneratedContentContains(
                "listedInputJOOQRecord",
                "customerForQuery(DSLContext ctx, List<CustomerRecord> inRecordList,",
                "DSL.row(_customer.getId(), _customer.FIRST_NAME).in(" +
                        "    inRecordList.stream().map(internal_it_ -> DSL.row(DSL.inline(internal_it_.getId()), DSL.inline(internal_it_.getFirstName()))).collect(Collectors.toList())" +
                        ") : DSL.noCondition()"
        );
    }
}
