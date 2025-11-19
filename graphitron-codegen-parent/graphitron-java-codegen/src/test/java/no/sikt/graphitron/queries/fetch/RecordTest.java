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
import static no.sikt.graphitron.common.configuration.ReferencedEntry.JAVA_RECORD_CUSTOMER;
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
        return makeReferences(DUMMY_RECORD, JAVA_RECORD_CUSTOMER);
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
                "customerForQuery(DSLContext _iv_ctx, DummyRecord _mi_inRecord,",
                "inRecord != null ? _a_customer.hasId(_mi_inRecord.getId()) : DSL.noCondition()",
                "inRecord != null ? _a_customer.hasId(_mi_inRecord.getOtherID()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Listed input Java records")
    void listedInputJavaRecord() {
        assertGeneratedContentContains(
                "listedInputJavaRecord",
                "customerForQuery(DSLContext _iv_ctx, List<DummyRecord> _mi_inRecordList,",
                "DSL.row(DSL.trueCondition(), DSL.trueCondition()).in(",
                "IntStream.range(0, _mi_inRecordList.size()).mapToObj(_iv_it -> DSL.row(_a_customer.hasId(_mi_inRecordList.get(_iv_it).getId()),",
                "customer.hasId(_mi_inRecordList.get(_iv_it).getOtherID())",
                ") : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Input jOOQ records")
    void inputJOOQRecord() {
        assertGeneratedContentContains(
                "inputJOOQRecord",
                "customerForQuery(DSLContext _iv_ctx, CustomerRecord _mi_inRecord,",
                "inRecord != null ? _a_customer.hasId(_mi_inRecord.getId()) : DSL.noCondition()",
                "inRecord != null ? _a_customer.FIRST_NAME.eq(_mi_inRecord.getFirstName()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Listed input jOOQ records")
    void listedInputJOOQRecord() {
        assertGeneratedContentContains(
                "listedInputJOOQRecord",
                "customerForQuery(DSLContext _iv_ctx, List<CustomerRecord> _mi_inRecordList,",
                "DSL.row(DSL.trueCondition(), _a_customer.FIRST_NAME).in(",
                "customer.hasId(_mi_inRecordList.get(_iv_it).getId()),",
                "_iv_select.getArgumentSet().contains(\"in[\" + _iv_it + \"]/first\") ? DSL.val(_mi_inRecordList.get(_iv_it).getFirstName()) : _a_customer.FIRST_NAME"
        );
    }

    @Test // Special case where nesting path should not be used since the records are flat structures.
    @DisplayName("Listed input jOOQ records with an extra input type inside")
    void listedNestedInputJOOQRecord() {
        assertGeneratedContentContains("listedNestedInputJOOQRecord", ".val(_mi_inRecordList.get(_iv_it).getFirstName())");
    }

    @Test
    @DisplayName("Listed input jOOQ records with a CLOB field")
    void listedInputJOOQRecordWithCLOB() {
        assertGeneratedContentContains("listedInputJOOQRecordWithCLOB", "_a_film.DESCRIPTION.cast(String.class)");
    }

    @Test // Not sure if this is allowed.
    @DisplayName("Input type with an ID field annotated with @field without @nodeId")
    void fieldOverrideID() {
        assertGeneratedContentContains("fieldOverrideID",
                "payment.hasCustomerId(_mi_inRecordList.get(_iv_it).getCustomerId()) : DSL.trueCondition()",
                "payment.hasPaymentId(_mi_inRecordList.get(_iv_it).getPaymentId()) : DSL.trueCondition()");
    }

    @Test // In these cases the table must be selected based on the input record, otherwise this is not resolvable.
    @DisplayName("Returns a type without a table set")
    void returningTypeWithoutTable() {
        assertGeneratedContentContains(
                "returningTypeWithoutTable", Set.of(CUSTOMER_INPUT_TABLE),
                ".row(_a_customer.getId(),_a_customer.FIRST_NAME",
                "CustomerNoTable::new",
                ".from(_a_customer)",
                "customer.hasId(_mi_inRecord.getId()",
                ".into(CustomerNoTable.class"
        );
    }

    @Test
    @DisplayName("Returns a type without a table set, which contains a table field")
    void returningTypeWithoutTableWithTableField() {
        assertGeneratedContentContains(
                "returningTypeWithoutTableWithTableField", Set.of(CUSTOMER_INPUT_TABLE, CUSTOMER_TABLE),
                ".row(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))).mapping(",
                ".where(_a_customer.hasId(_mi_inRecord.getId())).fetchOne(" // Makes sure there is no duplicated condition.
        );
    }

    @Test
    @DisplayName("Returns a listed type without a table set")
    void returningListedTypeWithoutTable() {
        assertGeneratedContentContains(
                "returningListedTypeWithoutTable", Set.of(CUSTOMER_INPUT_TABLE),
                ".row(DSL.row(DSL.multiset(" +
                        "DSL.select(_a_customer.getId())" +
                        ".from(_a_customer).where(_mi_inRecordList",
                ".mapping(Functions.nullOnAllNull((_iv_it) -> new CustomerNoTable(_iv_it))",
                ".fetchOne(_iv_it -> _iv_it.into(CustomerNoTable"
        );
    }

    @Test
    @DisplayName("Returns a listed type without a table set, which contains a table field")
    void returningListedTypeWithoutTableWithTableField() {
        assertGeneratedContentContains(
                "returningListedTypeWithoutTableWithTableField", Set.of(CUSTOMER_INPUT_TABLE, CUSTOMER_TABLE),
                ".row(DSL.row(DSL.multiset(" +
                        "DSL.select(_1_queryForQuery_customerNoTable_customers())",
                        "DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))",
                        ".from(_a_customer).where(_mi_inRecordList",
                "new CustomerNoTable(_iv_it)))",
                ".fetchOne(_iv_it -> _iv_it.into(CustomerNoTable"
        );
    }

    @Test // A too long and incorrect join path may occur here.
    @DisplayName("A listed type without a table set, which contains a table field and additional nesting layers with references")
    void returningListedTypeWithoutTableWithNestedTableField() {
        assertGeneratedContentContains(
                "returningListedTypeWithoutTableWithNestedTableField", Set.of(CUSTOMER_INPUT_TABLE),
                ".select(_a_customer_2168032777_address.DISTRICT).from(_a_customer_2168032777_address))).mapping"
        );
    }

    @Test
    @DisplayName("TableMethod with Java Record")
    void tableMethodWithJavaRecord() {
        assertGeneratedContentContains("javaRecordWithTableMethod",
                "customer = CUSTOMER.as(\"customer_2168032777\")",
                "customer = _rs_customerTableMethod.customerTable(_a_customer, _mi_inRecord)"
        );
    }

    @Test
    @DisplayName("TableMethod with jOOQ Record")
    void tableMethodWithJOOQRecord() {
        assertGeneratedContentContains("jOOQRecordWithTableMethod",
                "customer = CUSTOMER.as(\"customer_2168032777\")",
                "customer = _rs_customerTableMethod.customerTable(_a_customer, _mi_inRecord)"
        );
    }
}
