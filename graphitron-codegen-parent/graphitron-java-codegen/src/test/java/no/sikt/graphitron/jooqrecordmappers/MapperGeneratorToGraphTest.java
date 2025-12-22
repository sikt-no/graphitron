package no.sikt.graphitron.jooqrecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.RecordMapperClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;
import static no.sikt.graphitron.common.configuration.SchemaComponent.ADDRESS_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("JOOQ Mappers - Mapper content for mapping jOOQ records to graph types")
public class MapperGeneratorToGraphTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "jooqmappers/tograph";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, DUMMY_RECORD, DUMMY_JOOQ_ENUM, MAPPER_FETCH_SERVICE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Simple mapper with one field")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Field using the @field directive")
    void mappedField() {
        assertGeneratedContentContains(
                "mappedField",
                "pathHere + \"first\"",
                "customer.setFirst(_nit_customerRecord.getFirstName()"
        );
    }

    @Test // This is a special case that cause issues with the mapping logic.
    @DisplayName("Listed mapper source with nested fields")
    void listedSourceWithNesting() {
        assertGeneratedContentContains(
                "listedSourceWithNesting",
                "_mo_customer_name.setFirst(_nit_customerRecord.getFirstName()"
        );
    }

    @Test
    @DisplayName("Mapper with multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                "pathHere + \"id\"",
                "customer.setId(_nit_customerRecord.getId()",
                "pathHere + \"first\"",
                "customer.setFirst(_nit_customerRecord.getFirstName()"
        );
    }

    @Test
    @DisplayName("Mapper with a non-record outer wrapper")
    void outerNonRecordWrapper() {
        assertGeneratedContentContains(
                "outerNonRecordWrapper",
                "pathHere + \"customer\"",
                        "wrapper.setCustomer(_iv_transform.customerRecordToGraphType(_mi_wrapperRecord, _iv_pathHere + \"customer\")"
        );
    }

    @Test
    @DisplayName("Record containing jOOQ record")
    void containingRecords() {
        assertGeneratedContentContains(
                "containingRecords",
                "customer = new ArrayList<Customer>();return _mlo_customer"
        );
    }

    @Test
    @DisplayName("Record containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingNonRecordWrapper", Set.of(ADDRESS_SERVICE),
                "address_inner = new Wrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner/postalCode\")) {" +
                        "_mo_address_inner.setPostalCode(_nit_addressRecord.getPostalCode());}" +
                        "_mo_address.setInner(_mo_address_inner)"
        );
    }

    @Test
    @DisplayName("Mapper with two layers of non-record types")
    void containingDoubleNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingDoubleNonRecordWrapper", Set.of(ADDRESS_SERVICE),
                "address_inner0 = new Wrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner0/inner1\")) {" +
                        "var _mo_wrapper_inner1 = new InnerWrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner0/inner1/postalCode\")) {" +
                        "_mo_wrapper_inner1.setPostalCode(_nit_addressRecord.getPostalCode());}" +
                        "_mo_address_inner0.setInner1(_mo_wrapper_inner1);}" +
                        "_mo_address.setInner0(_mo_address_inner0)"
        );
    }

    @Test
    @DisplayName("Fields on different levels that have the same name")
    void nestingWithDuplicateFieldName() {
        assertGeneratedContentContains(
                "nestingWithDuplicateFieldName", Set.of(ADDRESS_SERVICE),
                "address_inner = new Wrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner/inner\")) {" +
                        "var _mo_wrapper_inner = new InnerWrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner/inner/postalCode\")) {" +
                        "_mo_wrapper_inner.setPostalCode(_nit_addressRecord.getPostalCode());}" +
                        "_mo_address_inner.setInner(_mo_wrapper_inner);}" +
                        "_mo_address.setInner(_mo_address_inner)"
        );
    }

    @Test
    @DisplayName("Record containing non-record type and using field overrides")
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithFieldOverride", Set.of(ADDRESS_SERVICE),
                "address_inner1 = new Wrapper1()",
                "address_inner1.setCode(_nit_addressRecord.getPostalCode()",
                "address_inner2 = new Wrapper2()",
                "address_inner2.setCode(_nit_addressRecord.getPostalCode()"
        );
    }

    @Test
    @DisplayName("Handles fields that are not mapped to a record field") // TODO: This currently produces illegal code.
    void unconfiguredField() {
        assertGeneratedContentContains(
                "unconfiguredField",
                ".setId1(_nit_customerRecord.getId1()",
                ".setId2(_nit_customerRecord.getWrongName()"
        );
    }

    @Test
    @DisplayName("Maps ID fields that are not the primary key")
    void idOtherThanPK() {
        assertGeneratedContentContains("idOtherThanPK", ".setAddressId(_nit_customerRecord.getAddressId()");
    }

    @Test
    @DisplayName("Enum field")
    void withEnum() {
        assertGeneratedContentContains("withEnum", Set.of(SchemaComponent.DUMMY_ENUM),".setE(QueryHelper.makeEnumMap(_nit_filmRecord.getRating(),");
    }

    @Test
    @DisplayName("SplitQuery field")
    void withSplitQueryReference() {
        assertGeneratedContentContains("withSplitQueryReference",
                "if (_iv_select.contains(_iv_pathHere + \"address\")) {_mo_customer.setAddressKey(DSL.row(_nit_customerRecord.getCustomerId()));}"
        );
    }

    @Test
    @DisplayName("SplitQuery list")
    void withSplitQueryList() {
        assertGeneratedContentContains("withSplitQueryList",
                "customer.setId(_nit_customerRecord.getId())",
                "if (_iv_select.contains(_iv_pathHere + \"customers\")) {_mo_customerPayload.setCustomersKey(_mi_customerPayloadRecord.stream().map(_iv_it -> DSL.row(_iv_it.getCustomerId())).toList());}"
        );
    }

    @Test
    @DisplayName("SplitQuery field with key provided in reference directive")
    void withSplitQueryReferenceWithKey() {
        assertGeneratedContentContains("withSplitQueryReferenceWithKey",
                "if (_iv_select.contains(_iv_pathHere + \"address\")) {_mo_customer.setAddressKey(DSL.row(_nit_customerRecord.getCustomerId()));}"
        );
    }

    @Test
    @DisplayName("SplitQuery list field with key provided in reference directive")
    void withSplitQueryReferenceWithKeyList() {
        assertGeneratedContentContains("withSplitQueryReferenceWithKeyList",
                "\"payments\")) {_mo_customer.setPaymentsKey(DSL.row(_nit_customerRecord.getCustomerId()))"
        );
    }

    @Test
    @DisplayName("SplitQuery field with condition and no key provided in reference directive")
    void withSplitQueryReferenceWithCondition() {
        assertGeneratedContentContains("withSplitQueryReferenceWithCondition",
                "\"address\")) {_mo_customer.setAddressKey(DSL.row(_nit_customerRecord.getCustomerId()))"
        );
    }

    @Test
    @DisplayName("SplitQuery list field with condition and no key provided in reference directive")
    void withSplitQueryReferenceWithConditionList() {
        assertGeneratedContentContains("withSplitQueryReferenceWithConditionList",
                "\"payments\")) {_mo_customer.setPaymentsKey(DSL.row(_nit_customerRecord.getCustomerId()))"
        );
    }

    @Test
    @DisplayName("With scalar array field")
    void arrayScalarField() {
        assertGeneratedContentContains(
                "arrayScalarField",
                "setUsernames(List.of(_nit_customerRecord.getUsernames()));"
        );
    }
}
