package no.sikt.graphitron.javarecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.JavaRecordMapperClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Java Mappers - Mapper content for mapping Java records to graph types")
public class JavaMapperGeneratorToGraphTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "javamappers/tograph";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, NESTED_RECORD, JAVA_RECORD_CUSTOMER, MAPPER_RECORD_ADDRESS, MAPPER_RECORD_ENUM);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Simple mapper with one field")
    void defaultCase() {
        assertGeneratedContentMatches("default", DUMMY_TYPE_RECORD);
    }

    @Test
    @DisplayName("Field using the @field directive")
    void mappedField() {
        assertGeneratedContentContains(
                "mappedField",
                "pathHere + \"id\"",
                "customer.setId(_nit_customerJavaRecord.getSomeID()"
        );
    }

    @Test
    @DisplayName("Mapper with multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                "pathHere + \"someID\"",
                "customer.setSomeID(_nit_customerJavaRecord.getSomeID()",
                "pathHere + \"otherID\"",
                "customer.setOtherID(_nit_customerJavaRecord.getOtherID()"
        );
    }

    @Test
    @DisplayName("Containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingNonRecordWrapper", Set.of(ADDRESS_SERVICE),
                "inner = new Wrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner/postalCode\")) {" +
                        "_mo_inner.setPostalCode(_nit_mapperAddressJavaRecord.getPostalCode());}" +
                        "_mo_address.setInner(_mo_inner)"
        );
    }

    @Test
    @DisplayName("Containing two layers of non-record types")
    void containingDoubleNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingDoubleNonRecordWrapper", Set.of(ADDRESS_SERVICE),
                        "if (_iv_select.contains(_iv_pathHere + \"inner0/inner1\")) {" +
                        "var _mo_inner1 = new InnerWrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner0/inner1/postalCode\")) {" +
                        "_mo_inner1.setPostalCode(_nit_mapperAddressJavaRecord.getPostalCode());}" +
                        "_mo_inner0.setInner1(_mo_inner1)"
        );
    }

    @Test
    @Disabled("Java records do not account for name duplicates in the schema like jOOQ ones do.")
    @DisplayName("Fields on different levels that have the same name")
    void nestingWithDuplicateFieldName() {
        assertGeneratedContentContains(
                "containingNonRecordWrapper",
                "inner = new Wrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner/inner\")) {" +
                        "var _mo_inner = new InnerWrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner/inner/postalCode\")) {" +
                        "_mo_inner.setPostalCode(_nit_mapperAddressJavaRecord.getPostalCode());}" +
                        "_mo_inner.setInner(_mo_inner)"
        );
    }

    @Test
    @DisplayName("Containing non-record type and using field overrides")
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithFieldOverride", Set.of(ADDRESS_SERVICE),
                "inner1 = new Wrapper1()",
                "inner1.setCode(_nit_mapperAddressJavaRecord.getPostalCode()",
                "inner2 = new Wrapper2()",
                "inner2.setCode(_nit_mapperAddressJavaRecord.getPostalCode()"
        );
    }

    @Test
    @DisplayName("Sets resolver key for field with splitQuery")
    void splitQuery() {
        assertGeneratedContentContains(
                "splitQuery",
                "var _mi_address = _nit_customerJavaRecord.getAddress();" +
                        "if (_mi_address != null && _iv_select.contains(_iv_pathHere + \"address\")) {" +
                        "_mo_customer.setAddressKey(DSL.row(_mi_address.getAddressId()));"
        );
    }

    @Test
    @DisplayName("Sets resolver key for nested field with splitQuery")
    void splitQueryNested() {
        assertGeneratedContentContains(
                "splitQueryNested",
                "var _mi_address = _nit_customerJavaRecord.getAddress();" +
                        "if (_mi_address != null && _iv_select.contains(_iv_pathHere + \"wrapper/address\")) {" +
                        "_mo_wrapper.setAddressKey(DSL.row(_mi_address.getAddressId()));"
        );
    }

    @Test
    @DisplayName("Sets resolver keys for listed field with splitQuery")
    void splitQueryListed() {
        assertGeneratedContentContains(
                "splitQueryListed",
                "var _mi_address = _nit_customerJavaRecord.getAddress()",
                "customer.setAddressKey(_mi_address.stream().map(_iv_it -> DSL.row(_iv_it.getAddressId())).toList());"
        );
    }

    @Test
    @DisplayName("Skips fields that are not mapped to a record field")
    void unconfiguredField() {
        assertGeneratedContentContains(
                "unconfiguredField",
                "customer = new ArrayList<Customer>();return _mlo_customer"
        );
    }

    @Test
    @DisplayName("Maps ID fields that are not the primary key")
    void idOtherThanPK() {
        assertGeneratedContentContains(
                "idOtherThanPK",
                "pathHere + \"addressId\"",
                ".setAddressId(_nit_customerJavaRecord.getAddressId()"
        );
    }

    @Test
    @DisplayName("Enum field")
    void withEnum() {
        assertGeneratedContentContains("withEnum", Set.of(SchemaComponent.DUMMY_ENUM),".setE(QueryHelper.makeEnumMap(_nit_mapperEnumRecord.getEnum1(),");
    }

    @Test
    @DisplayName("List field")
    void listField() {
        assertGeneratedContentContains("listField", ".setIdList(_nit_customerJavaRecord.getIdList()");
    }

    @Test
    @DisplayName("Responses containing a jOOQ record with split query")
    void recordWithSplitQuery() {
        resultDoesNotContain("containingRecordWithSplitQuery", Set.of(ADDRESS_SERVICE), "address.setCustomer(");
    }

    @Test
    @Disabled("This is wrong! Should be _nit_mapperNestedJavaRecord.getDummyRecord()). Seems to wrongly use toRecord case here.")
    @DisplayName("Responses containing a Java record with a non-record type in between")
    void containingNonRecordWrapperWithJavaRecord() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithJavaRecord", Set.of(DUMMY_TYPE_RECORD),
                "inner.setDummyRecord(_iv_transform.dummyTypeRecordToGraphType(_nit_mapperNestedJavaRecord.getDummyRecord(), _iv_pathHere + \"inner/dummyRecord\")"
        );
    }

    @Test
    @Disabled("This is wrong! Should be _nit_mapperNestedJavaRecord.getCustomer(). Seems to wrongly use toRecord case here.")
    @DisplayName("Responses containing a jOOQ record with a non-record type in between")
    void containingNonRecordWrapperWithJOOQRecord() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithJOOQRecord", Set.of(CUSTOMER_TABLE),
                "inner.setCustomer(_iv_transform.customerTableRecordToGraphType(_nit_mapperNestedJavaRecord.getCustomer(), _iv_pathHere + \"inner/customer\")"
        );
    }

    @Test
    @DisplayName("Responses skip fields that are not mapped to a Java record")
    void withUnconfiguredJavaRecord() {
        assertGeneratedContentContains(
                "unconfiguredJavaRecord",
                "customer = new ArrayList<Customer>();return _mlo_customer"
        );
    }

    @Test
    @DisplayName("Responses skip fields that are not mapped to a jOOQ record")
    void withUnconfiguredJOOQRecord() {
        assertGeneratedContentContains(
                "unconfiguredJOOQRecord", Set.of(CUSTOMER_TABLE),
                "customer = new ArrayList<Customer>();return _mlo_customer"
        );
    }
}
