package no.sikt.graphitron.javarecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
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
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
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
                "customer.setId(itCustomerJavaRecord.getSomeID()"
        );
    }

    @Test
    @DisplayName("Mapper with multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                "pathHere + \"someID\"",
                "customer.setSomeID(itCustomerJavaRecord.getSomeID()",
                "pathHere + \"otherID\"",
                "customer.setOtherID(itCustomerJavaRecord.getOtherID()"
        );
    }

    @Test
    @DisplayName("Containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingNonRecordWrapper", Set.of(ADDRESS_SERVICE),
                "inner = new Wrapper();" +
                        "if (select.contains(pathHere + \"inner/postalCode\")) {" +
                        "    inner.setPostalCode(itMapperAddressJavaRecord.getPostalCode());" +
                        "}" +
                        "address.setInner(inner)"
        );
    }

    @Test
    @DisplayName("Containing two layers of non-record types")
    void containingDoubleNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingDoubleNonRecordWrapper", Set.of(ADDRESS_SERVICE),
                        "if (select.contains(pathHere + \"inner0/inner1\")) {" +
                        "    var inner1 = new InnerWrapper();" +
                        "    if (select.contains(pathHere + \"inner0/inner1/postalCode\")) {" +
                        "        inner1.setPostalCode(itMapperAddressJavaRecord.getPostalCode());" +
                        "    }" +
                        "    inner0.setInner1(inner1)"
        );
    }

    @Test
    @Disabled("Java records do not account for name duplicates in the schema like jOOQ ones do.")
    @DisplayName("Fields on different levels that have the same name")
    void nestingWithDuplicateFieldName() {
        assertGeneratedContentContains(
                "containingNonRecordWrapper",
                "inner = new Wrapper();" +
                        "if (select.contains(pathHere + \"inner/inner\")) {" +
                        "    var inner = new InnerWrapper();" +
                        "    if (select.contains(pathHere + \"inner/inner/postalCode\")) {" +
                        "        inner.setPostalCode(itMapperAddressJavaRecord.getPostalCode());" +
                        "    }" +
                        "    inner.setInner(inner)"
        );
    }

    @Test
    @DisplayName("Containing non-record type and using field overrides")
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithFieldOverride", Set.of(ADDRESS_SERVICE),
                "inner1 = new Wrapper1()",
                "inner1.setCode(itMapperAddressJavaRecord.getPostalCode()",
                "inner2 = new Wrapper2()",
                "inner2.setCode(itMapperAddressJavaRecord.getPostalCode()"
        );
    }

    @Test
    @DisplayName("Skips fields with splitQuery set")
    void skipsSplitQuery() {
        assertGeneratedContentContains(
                "skipsSplitQuery",
                "customerList = new ArrayList<Customer>();return customerList"
        );
    }

    @Test
    @DisplayName("Skips fields that are not mapped to a record field")
    void unconfiguredField() {
        assertGeneratedContentContains(
                "unconfiguredField",
                "customerList = new ArrayList<Customer>();return customerList"
        );
    }

    @Test
    @DisplayName("Maps ID fields that are not the primary key")
    void idOtherThanPK() {
        assertGeneratedContentContains(
                "idOtherThanPK",
                "pathHere + \"addressId\"",
                ".setAddressId(itCustomerJavaRecord.getAddressId()"
        );
    }

    @Test
    @DisplayName("Enum field")
    void withEnum() {
        assertGeneratedContentContains("withEnum", Set.of(SchemaComponent.DUMMY_ENUM),".setE(itMapperEnumRecord.getEnum1() == null ?");
    }

    @Test
    @DisplayName("List field")
    void listField() {
        assertGeneratedContentContains("listField", ".setIdList(itCustomerJavaRecord.getIdList()");
    }

    @Test
    @DisplayName("Responses containing a jOOQ record fetched by ID")
    void recordFetchedByID() {
        assertGeneratedContentContains(
                "containingRecordFetchedByID", Set.of(ADDRESS_SERVICE),
                "address.setCustomer(CustomerDBQueries.loadCustomerByIdsAsNode(" +
                        "transform.getCtx(), Set.of(customer.getId()), select.withPrefix(pathHere + \"customer\"" +
                        ")).values().stream().findFirst().orElse(null)"
        );
    }

    @Test
    @DisplayName("Responses containing listed jOOQ records fetched by ID")
    void listedRecordFetchedByID() {
        assertGeneratedContentContains(
                "containingListedRecordFetchedByID", Set.of(ADDRESS_SERVICE),
                "loadCustomerByIdsAsNode = CustomerDBQueries.loadCustomerByIdsAsNode(" +
                        "transform.getCtx(), customerList.stream().map(it -> it.getId()).collect(Collectors.toSet()), select.withPrefix(pathHere + \"customerList\"));" +
                        "address.setCustomerList(customerList.stream().map(it -> loadCustomerByIdsAsNode.get(it.getId())).collect(Collectors.toList())"
        );
    }

    @Test
    @Disabled("This is wrong! Should be itMapperNestedJavaRecord.getDummyRecord()). Seems to wrongly use toRecord case here.")
    @DisplayName("Responses containing a Java record with a non-record type in between")
    void containingNonRecordWrapperWithJavaRecord() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithJavaRecord", Set.of(DUMMY_TYPE_RECORD),
                "inner.setDummyRecord(transform.dummyTypeRecordToGraphType(itMapperNestedJavaRecord.getDummyRecord(), pathHere + \"inner/dummyRecord\")"
        );
    }

    @Test
    @Disabled("This is wrong! Should be itMapperNestedJavaRecord.getCustomer(). Seems to wrongly use toRecord case here.")
    @DisplayName("Responses containing a jOOQ record with a non-record type in between")
    void containingNonRecordWrapperWithJOOQRecord() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithJOOQRecord", Set.of(CUSTOMER_TABLE),
                "inner.setCustomer(transform.customerTableRecordToGraphType(itMapperNestedJavaRecord.getCustomer(), pathHere + \"inner/customer\")"
        );
    }

    @Test
    @DisplayName("Responses skip fields that are not mapped to a Java record")
    void withUnconfiguredJavaRecord() {
        assertGeneratedContentContains(
                "unconfiguredJavaRecord",
                "customerList = new ArrayList<Customer>();return customerList"
        );
    }

    @Test
    @DisplayName("Responses skip fields that are not mapped to a jOOQ record")
    void withUnconfiguredJOOQRecord() {
        assertGeneratedContentContains(
                "unconfiguredJOOQRecord", Set.of(CUSTOMER_TABLE),
                "customerList = new ArrayList<Customer>();return customerList"
        );
    }
}
