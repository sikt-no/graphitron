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
                "customer.setFirst(itCustomerRecord.getFirstName()"
        );
    }

    @Test
    @DisplayName("Mapper with multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                "pathHere + \"id\"",
                "customer.setId(itCustomerRecord.getId()",
                "pathHere + \"first\"",
                "customer.setFirst(itCustomerRecord.getFirstName()"
        );
    }

    @Test
    @DisplayName("Mapper with a non-record outer wrapper")
    void outerNonRecordWrapper() {
        assertGeneratedContentContains(
                "outerNonRecordWrapper",
                "pathHere + \"customer\"",
                        "wrapper.setCustomer(_iv_transform.customerRecordToGraphType(wrapperRecord, _iv_pathHere + \"customer\")"
        );
    }

    @Test
    @DisplayName("Record containing jOOQ record")
    void containingRecords() {
        assertGeneratedContentContains(
                "containingRecords",
                "customerList = new ArrayList<Customer>();return customerList"
        );
    }

    @Test
    @DisplayName("Record containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingNonRecordWrapper", Set.of(ADDRESS_SERVICE),
                "address_inner = new Wrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner/postalCode\")) {" +
                        "address_inner.setPostalCode(itAddressRecord.getPostalCode());}" +
                        "address.setInner(address_inner)"
        );
    }

    @Test
    @DisplayName("Mapper with two layers of non-record types")
    void containingDoubleNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingDoubleNonRecordWrapper", Set.of(ADDRESS_SERVICE),
                "address_inner0 = new Wrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner0/inner1\")) {" +
                        "var wrapper_inner1 = new InnerWrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner0/inner1/postalCode\")) {" +
                        "wrapper_inner1.setPostalCode(itAddressRecord.getPostalCode());}" +
                        "address_inner0.setInner1(wrapper_inner1);}" +
                        "address.setInner0(address_inner0)"
        );
    }

    @Test
    @DisplayName("Fields on different levels that have the same name")
    void nestingWithDuplicateFieldName() {
        assertGeneratedContentContains(
                "nestingWithDuplicateFieldName", Set.of(ADDRESS_SERVICE),
                "address_inner = new Wrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner/inner\")) {" +
                        "var wrapper_inner = new InnerWrapper();" +
                        "if (_iv_select.contains(_iv_pathHere + \"inner/inner/postalCode\")) {" +
                        "wrapper_inner.setPostalCode(itAddressRecord.getPostalCode());}" +
                        "address_inner.setInner(wrapper_inner);}" +
                        "address.setInner(address_inner)"
        );
    }

    @Test
    @DisplayName("Record containing non-record type and using field overrides")
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithFieldOverride", Set.of(ADDRESS_SERVICE),
                "address_inner1 = new Wrapper1()",
                "address_inner1.setCode(itAddressRecord.getPostalCode()",
                "address_inner2 = new Wrapper2()",
                "address_inner2.setCode(itAddressRecord.getPostalCode()"
        );
    }

    @Test
    @DisplayName("Handles fields that are not mapped to a record field") // TODO: This currently produces illegal code.
    void unconfiguredField() {
        assertGeneratedContentContains(
                "unconfiguredField",
                ".setId1(itCustomerRecord.getId1()",
                ".setId2(itCustomerRecord.getWrongName()"
        );
    }

    @Test
    @DisplayName("Maps ID fields that are not the primary key")
    void idOtherThanPK() {
        assertGeneratedContentContains("idOtherThanPK", ".setAddressId(itCustomerRecord.getAddressId()");
    }

    @Test
    @DisplayName("Enum field")
    void withEnum() {
        assertGeneratedContentContains("withEnum", Set.of(SchemaComponent.DUMMY_ENUM),".setE(QueryHelper.makeEnumMap(itFilmRecord.getRating(),");
    }

    @Test
    @DisplayName("SplitQuery field")
    void withSplitQueryReference() {
        assertGeneratedContentContains("withSplitQueryReference",
                "if (_iv_select.contains(_iv_pathHere + \"address\")) {customer.setAddressKey(DSL.row(itCustomerRecord.getCustomerId()));}"
        );
    }

    @Test
    @DisplayName("SplitQuery list")
    void withSplitQueryList() {
        assertGeneratedContentContains("withSplitQueryList",
                "if (_iv_select.contains(_iv_pathHere + \"customers\")) {customerPayload.setCustomersKey(customerPayloadRecord.stream().map(_iv_it -> DSL.row(_iv_it.getCustomerId())).toList());}"
        );
    }

}
