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
import java.util.Optional;
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
        return makeReferences(DUMMY_SERVICE, NESTED_RECORD, MAPPER_RECORD_ENUM, JAVA_RECORD_CUSTOMER, MAPPER_RECORD_ADDRESS, OPTIONAL_FIELD_RECORD);
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
        assertGeneratedContentContains("mappedField", "customerJavaRecord.setSomeID(_nit_customer.getId()");
    }

    @Test
    @DisplayName("Mapper with multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                "_iv_args.hasField(\"id\")",
                "customerJavaRecord.setSomeID(_nit_customer.getId()",
                "_iv_args.hasField(\"otherID\")",
                "customerJavaRecord.setOtherID(_nit_customer.getOtherID()"
        );
    }

    @Test
    @DisplayName("Containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingNonRecordWrapper",
                "inner = _nit_address.getInner();" +
                        "if (_mi_inner != null && _iv_args.hasField(\"inner\")) {" +
                        "if (_iv_args.child(\"inner\").hasField(\"postalCode\")) {" +
                        "_mo_mapperAddressJavaRecord.setPostalCode(_mi_inner.getPostalCode());"
        );
    }

    @Test
    @DisplayName("Containing two layers of non-record types")
    void containingDoubleNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingDoubleNonRecordWrapper",
                        "if (_mi_inner0 != null && _iv_args.hasField(\"inner0\")) {" +
                        "var _mi_inner1 = _mi_inner0.getInner1();" +
                        "if (_mi_inner1 != null && _iv_args.child(\"inner0\").hasField(\"inner1\")) {" +
                        "if (_iv_args.child(\"inner0\").child(\"inner1\").hasField(\"postalCode\")) {" +
                        "_mo_mapperAddressJavaRecord.setPostalCode(_mi_inner1.getPostalCode());"
        );
    }

    @Test
    @Disabled("Java records do not account for name duplicates in the schema like jOOQ ones do.")
    @DisplayName("Fields on different levels that have the same name")
    void nestingWithDuplicateFieldName() {
        assertGeneratedContentContains(
                "nestingWithDuplicateFieldName",
                "inner = itAddress.getInner();" +
                        "if (inner != null && _iv_args.hasField(\"inner\")) {" +
                        "var inner = inner.getInner();" +
                        "if (inner != null && _iv_args.child(\"inner\").hasField(\"inner\")) {" +
                        "if (_iv_args.child(\"inner\").child(\"inner\").hasField(\"postalCode\")) {" +
                        "mapperAddressJavaRecord.setPostalCode(inner.getPostalCode()"
        );
    }


    @Test
    @DisplayName("Containing non-record type and using field overrides")
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithFieldOverride",
                "inner1 = _nit_address.getInner1()",
                ".setPostalCode(_mi_inner1.getCode()",
                "inner2 = _nit_address.getInner2()",
                ".setPostalCode(_mi_inner2.getCode()"
        );
    }

    @Test
    @DisplayName("Skips fields that are not mapped to a record field")
    void unconfiguredField() {
        assertGeneratedContentContains(
                "unconfiguredField",
                "customerJavaRecord = new ArrayList<CustomerJavaRecord>();return _mlo_customerJavaRecord"
        );
    }

    @Test
    @DisplayName("Maps ID fields that are not the primary key")
    void idOtherThanPK() {
        assertGeneratedContentContains(
                "idOtherThanPK",
                "_iv_args.hasField(\"addressId\")",
                ".setAddressId(_nit_customer.getAddressId()"
        );
    }

    @Test
    @DisplayName("Skips fields with splitQuery set")
    void skipsSplitQuery() {
        assertGeneratedContentContains(
                "skipsSplitQuery",
                "customerJavaRecord = new ArrayList<CustomerJavaRecord>();return _mlo_customerJavaRecord"
        );
    }

    @Test
    @DisplayName("Enum fields")
    void withEnum() {
        assertGeneratedContentContains("withEnum", Set.of(SchemaComponent.DUMMY_ENUM), ".setEnum1(QueryHelper.makeEnumMap(_nit_dummy.getE(),");
    }

    @Test
    @DisplayName("List fields")
    void listField() {
        assertGeneratedContentContains("listField", ".setIdList(_nit_customer.getIdList()");
    }

    @Test
    @DisplayName("Inputs containing a Java record with a non-record type in between")
    void containingNonRecordWrapperWithJavaRecord() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithJavaRecord", Set.of(DUMMY_INPUT_RECORD),
                "mapperNestedJavaRecord.setDummyRecord(_iv_transform.dummyInputRecordToJavaRecord(_mi_dummyRecord, _iv_args.child(\"dummyRecord\"), _iv_path + \"[\" + _niit_address + \"]/inner/dummyRecord\")"
        );
    }

    @Test
    @DisplayName("Inputs containing a jOOQ record with a non-record type in between")
    void containingNonRecordWrapperWithJOOQRecord() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithJOOQRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "mapperNestedJavaRecord.setCustomer(_iv_transform.customerInputTableToJOOQRecord(_mi_customer, _iv_args.child(\"customer\"), _iv_path + \"[\" + _niit_address + \"]/inner/customer\")"
        );
    }

    @Test
    @DisplayName("Optional setter fields are wrapped in Optional.ofNullable()")
    void optionalFields() {
        assertGeneratedContentContains("optionalFields", Set.of(OPTIONAL_FIELD_INPUT),
                "setId(_nit_optionalFieldInput.getId())",
                "setName(Optional.ofNullable(_nit_optionalFieldInput.getName()))",
                "setRentalDuration(Optional.ofNullable(_nit_optionalFieldInput.getRentalDuration()))");
    }

    @Test
    @DisplayName("Inputs skip fields that are not mapped to a Java record")
    void withUnconfiguredJavaRecord() {
        assertGeneratedContentContains(
                "unconfiguredJavaRecord", Set.of(DUMMY_INPUT_RECORD),
                "customerJavaRecord = new ArrayList<CustomerJavaRecord>();return _mlo_customerJavaRecord"
        );
    }

    @Test
    @DisplayName("Inputs skip fields that are not mapped to a jOOQ record")
    void withUnconfiguredJOOQRecord() {
        assertGeneratedContentContains(
                "unconfiguredJOOQRecord", Set.of(CUSTOMER_TABLE),
                "customerJavaRecord = new ArrayList<CustomerJavaRecord>();return _mlo_customerJavaRecord"
        );
    }
}
