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

@DisplayName("Java Mappers - Mapper content for mapping graph types to Java records")
public class JavaMapperGeneratorToRecordTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "javamappers/torecord";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, NESTED_RECORD, MAPPER_RECORD_ENUM, JAVA_RECORD_CUSTOMER, MAPPER_RECORD_ADDRESS);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, true));
    }

    @Test
    @DisplayName("Simple mapper with one field")
    void defaultCase() {
        assertGeneratedContentMatches("default", DUMMY_INPUT_RECORD);
    }

    @Test
    @DisplayName("Field using the @field directive")
    void mappedField() {
        assertGeneratedContentContains("mappedField", "customerJavaRecord.setSomeID(itCustomer.getId()");
    }

    @Test
    @DisplayName("Mapper with multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                "pathHere + \"id\"",
                "customerJavaRecord.setSomeID(itCustomer.getId()",
                "pathHere + \"otherID\"",
                "customerJavaRecord.setOtherID(itCustomer.getOtherID()"
        );
    }

    @Test
    @DisplayName("Containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingNonRecordWrapper",
                "inner = itAddress.getInner();" +
                        "if (inner != null && _iv_args.contains(_iv_pathHere + \"inner\")) {" +
                        "    if (_iv_args.contains(_iv_pathHere + \"inner/postalCode\")) {" +
                        "        mapperAddressJavaRecord.setPostalCode(inner.getPostalCode());"
        );
    }

    @Test
    @DisplayName("Containing two layers of non-record types")
    void containingDoubleNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingDoubleNonRecordWrapper",
                        "if (inner0 != null && _iv_args.contains(_iv_pathHere + \"inner0\")) {" +
                        "    var inner1 = inner0.getInner1();" +
                        "    if (inner1 != null && _iv_args.contains(_iv_pathHere + \"inner0/inner1\")) {" +
                        "        if (_iv_args.contains(_iv_pathHere + \"inner0/inner1/postalCode\")) {" +
                        "            mapperAddressJavaRecord.setPostalCode(inner1.getPostalCode());"
        );
    }

    @Test
    @Disabled("Java records do not account for name duplicates in the schema like jOOQ ones do.")
    @DisplayName("Fields on different levels that have the same name")
    void nestingWithDuplicateFieldName() {
        assertGeneratedContentContains(
                "nestingWithDuplicateFieldName",
                "inner = itAddress.getInner();" +
                        "if (inner != null && _iv_args.contains(_iv_pathHere + \"inner\")) {" +
                        "    var inner = inner.getInner();" +
                        "    if (inner != null && _iv_args.contains(_iv_pathHere + \"inner/inner\")) {" +
                        "        if (_iv_args.contains(_iv_pathHere + \"inner/inner/postalCode\")) {" +
                        "            mapperAddressJavaRecord.setPostalCode(inner.getPostalCode()"
        );
    }


    @Test
    @DisplayName("Containing non-record type and using field overrides")
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithFieldOverride",
                "inner1 = itAddress.getInner1()",
                ".setPostalCode(inner1.getCode()",
                "inner2 = itAddress.getInner2()",
                ".setPostalCode(inner2.getCode()"
        );
    }

    @Test
    @DisplayName("Skips fields that are not mapped to a record field")
    void unconfiguredField() {
        assertGeneratedContentContains(
                "unconfiguredField",
                "customerJavaRecordList = new ArrayList<CustomerJavaRecord>();return customerJavaRecordList"
        );
    }

    @Test
    @DisplayName("Maps ID fields that are not the primary key")
    void idOtherThanPK() {
        assertGeneratedContentContains(
                "idOtherThanPK",
                "pathHere + \"addressId\"",
                ".setAddressId(itCustomer.getAddressId()"
        );
    }

    @Test
    @DisplayName("Skips fields with splitQuery set")
    void skipsSplitQuery() {
        assertGeneratedContentContains(
                "skipsSplitQuery",
                "customerJavaRecordList = new ArrayList<CustomerJavaRecord>();return customerJavaRecordList"
        );
    }

    @Test
    @DisplayName("Enum fields")
    void withEnum() {
        assertGeneratedContentContains("withEnum", Set.of(SchemaComponent.DUMMY_ENUM), ".setEnum1(QueryHelper.makeEnumMap(itDummy.getE(),");
    }

    @Test
    @DisplayName("List fields")
    void listField() {
        assertGeneratedContentContains("listField", ".setIdList(itCustomer.getIdList()");
    }

    @Test
    @DisplayName("Inputs containing a Java record with a non-record type in between")
    void containingNonRecordWrapperWithJavaRecord() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithJavaRecord", Set.of(DUMMY_INPUT_RECORD),
                "mapperNestedJavaRecord.setDummyRecord(_iv_transform.dummyInputRecordToJavaRecord(dummyRecord, _iv_pathHere + \"inner/dummyRecord\")"
        );
    }

    @Test
    @DisplayName("Inputs containing a jOOQ record with a non-record type in between")
    void containingNonRecordWrapperWithJOOQRecord() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithJOOQRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "mapperNestedJavaRecord.setCustomer(_iv_transform.customerInputTableToJOOQRecord(customer, _iv_pathHere + \"inner/customer\")"
        );
    }

    @Test
    @DisplayName("Inputs skip fields that are not mapped to a Java record")
    void withUnconfiguredJavaRecord() {
        assertGeneratedContentContains(
                "unconfiguredJavaRecord", Set.of(DUMMY_INPUT_RECORD),
                "customerJavaRecordList = new ArrayList<CustomerJavaRecord>();return customerJavaRecordList"
        );
    }

    @Test
    @DisplayName("Inputs skip fields that are not mapped to a jOOQ record")
    void withUnconfiguredJOOQRecord() {
        assertGeneratedContentContains(
                "unconfiguredJOOQRecord", Set.of(CUSTOMER_TABLE),
                "customerJavaRecordList = new ArrayList<CustomerJavaRecord>();return customerJavaRecordList"
        );
    }
}
