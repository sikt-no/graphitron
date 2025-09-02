package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_RECORD;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
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
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
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
                "DSL.row(DSL.trueCondition(), DSL.trueCondition()).in(" +
                        "inRecordList.stream().map(internal_it_ -> DSL.row(_customer.hasId(internal_it_.getId()), _customer.hasId(internal_it_.getOtherID()))).toList()" +
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
                "DSL.row(DSL.trueCondition(), _customer.FIRST_NAME).in(" +
                        "inRecordList.stream().map(internal_it_ -> DSL.row(_customer.hasId(internal_it_.getId()), DSL.inline(internal_it_.getFirstName()))).toList()" +
                        ") : DSL.noCondition()"
        );
    }

    @Test // Special case where nesting path should not be used since the records are flat structures.
    @DisplayName("Listed input jOOQ records with an extra input type inside")
    void listedNestedInputJOOQRecord() {
        assertGeneratedContentContains("listedNestedInputJOOQRecord", ".inline(internal_it_.getFirstName())");
    }

    @Test
    @DisplayName("Listed input jOOQ records with a CLOB field")
    void listedInputJOOQRecordWithCLOB() {
        assertGeneratedContentContains("listedInputJOOQRecordWithCLOB", "_film.DESCRIPTION.cast(String.class)");
    }

    @Test // Not sure if this is allowed.
    @DisplayName("Input type with an ID field annotated with @field without @nodeId")
    void fieldOverrideID() {
        assertGeneratedContentContains("fieldOverrideID", "_payment.hasCustomerId(internal_it_.getCustomerId())", "_payment.hasPaymentId(internal_it_.getPaymentId())");
    }

    @Test // In these cases the table must be selected based on the input record, otherwise this is not resolvable.
    @DisplayName("Returns a type without a table set")
    void returningTypeWithoutTable() {
        assertGeneratedContentContains(
                "returningTypeWithoutTable", Set.of(CUSTOMER_INPUT_TABLE),
                ".row(_customer.getId(),_customer.FIRST_NAME",
                "CustomerNoTable::new",
                ".from(_customer)",
                "_customer.hasId(inRecord.getId()",
                ".into(CustomerNoTable.class"
        );
    }

    @Test
    @DisplayName("Returns a type without a table set, which contains a table field")
    void returningTypeWithoutTableWithTableField() {
        assertGeneratedContentContains(
                "returningTypeWithoutTableWithTableField", Set.of(CUSTOMER_INPUT_TABLE, CUSTOMER_TABLE),
                ".row(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))).mapping(",
                ".where(_customer.hasId(inRecord.getId())).fetchOne(" // Makes sure there is no duplicated condition.
        );
    }

    @Test
    @DisplayName("Returns a listed type without a table set")
    void returningListedTypeWithoutTable() {
        assertGeneratedContentContains(
                "returningListedTypeWithoutTable", Set.of(CUSTOMER_INPUT_TABLE),
                ".row(DSL.row(DSL.multiset(" +
                        "DSL.select(_customer.getId())" +
                        ".from(_customer).where(inRecordList",
                ".mapping(Functions.nullOnAllNull((internal_it_) -> new CustomerNoTable(internal_it_)))).fetchOne(it -> it.into(CustomerNoTable"
        );
    }

    @Test
    @DisplayName("Returns a listed type without a table set, which contains a table field")
    void returningListedTypeWithoutTableWithTableField() {
        assertGeneratedContentContains(
                "returningListedTypeWithoutTableWithTableField", Set.of(CUSTOMER_INPUT_TABLE, CUSTOMER_TABLE),
                ".row(DSL.row(DSL.multiset(" +
                        "DSL.select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))" +
                        ".from(_customer).where(inRecordList",
                "new CustomerNoTable(internal_it_)))).fetchOne(it -> it.into(CustomerNoTable"
        );
    }

    @Test // A too long and incorrect join path may occur here.
    @DisplayName("A listed type without a table set, which contains a table field and additional nesting layers with references")
    void returningListedTypeWithoutTableWithNestedTableField() {
        assertGeneratedContentContains(
                "returningListedTypeWithoutTableWithNestedTableField", Set.of(CUSTOMER_INPUT_TABLE),
                ".select(customer_2952383337_address.DISTRICT).from(customer_2952383337_address))).mapping"
        );
    }
}
