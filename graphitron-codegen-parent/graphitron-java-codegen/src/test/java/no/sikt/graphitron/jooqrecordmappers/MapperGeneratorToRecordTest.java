package no.sikt.graphitron.jooqrecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.RecordMapperClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;

@DisplayName("JOOQ Mappers - Mapper content for mapping graph types to jOOQ records")
public class MapperGeneratorToRecordTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "jooqmappers/torecord";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, DUMMY_JOOQ_ENUM, MAPPER_FETCH_SERVICE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, true));
    }

    @Test
    @DisplayName("Simple mapper with one field")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_INPUT_TABLE);
    }

    @Test
    @DisplayName("Field using the @field directive")
    void mappedField() {
        assertGeneratedContentContains(
                "mappedField",
                "pathHere + \"first\"",
                "customerRecord.setFirstName(_nit_customer.getFirst()"
        );
    }

    @Test
    @DisplayName("Mapper with multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                "pathHere + \"id\"",
                "customerRecord.setId(_nit_customer.getId()",
                "pathHere + \"first\"",
                "customerRecord.setFirstName(_nit_customer.getFirst()"
        );
    }

    @Test
    @DisplayName("Record containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingNonRecordWrapper",
                "if (_mi_address_inner != null) {" +
                        "if (_iv_args.contains(_iv_pathHere + \"inner/postalCode\")) {" +
                        "_mo_addressRecord.setPostalCode(_mi_address_inner.getPostalCode());}}" +
                        "_mlo_addressRecord.add(_mo_addressRecord)"
        );
    }

    @Test
    @DisplayName("Mapper with two layers of non-record types")
    void containingDoubleNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingDoubleNonRecordWrapper",
                "address_inner0 = _nit_address.getInner0();" +
                        "if (_mi_address_inner0 != null) {" +
                        "var _mi_wrapper_inner1 = _mi_address_inner0.getInner1();" +
                        "if (_mi_wrapper_inner1 != null) {" +
                        "if (_iv_args.contains(_iv_pathHere + \"inner0/inner1/postalCode\")) {" +
                        "_mo_addressRecord.setPostalCode(_mi_wrapper_inner1.getPostalCode());"
        );
    }

    @Test
    @DisplayName("Fields on different levels that have the same name")
    void nestingWithDuplicateFieldName() {
        assertGeneratedContentContains(
                "nestingWithDuplicateFieldName",
                "address_inner = _nit_address.getInner();" +
                        "if (_mi_address_inner != null) {" +
                        "var _mi_wrapper_inner = _mi_address_inner.getInner();" +
                        "if (_mi_wrapper_inner != null) {" +
                        "if (_iv_args.contains(_iv_pathHere + \"inner/inner/postalCode\")) {" +
                        "_mo_addressRecord.setPostalCode(_mi_wrapper_inner.getPostalCode()"
        );
    }

    @Test
    @DisplayName("Record containing non-record type and using field overrides")
    @Disabled("This confuses the temporary variable a bit, but otherwise works.")
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithFieldOverride",
                "address_inner1 = _nit_address.getInner1()",
                ".setPostalCode(_mi_address_inner1.getCode()",
                "address_inner2 = _nit_address.getInner2()",
                ".setPostalCode(_mi_address_inner2.getCode()"
        );
    }

    @Test
    @DisplayName("Handles fields that are not mapped to a record field") // TODO: This currently produces illegal code.
    void unconfiguredField() {
        assertGeneratedContentContains(
                "unconfiguredField",
                ".setId1(_nit_customer.getId1()",
                ".setWrongName(_nit_customer.getId2()"
        );
    }

    @Test
    @DisplayName("Maps ID fields that are not the primary key")
    void idOtherThanPK() {
        assertGeneratedContentContains("idOtherThanPK", ".setAddressId(_nit_customer.getAddressId()");
    }

    @Test
    @DisplayName("Records with enum fields")
    void withEnum() {
        assertGeneratedContentContains("withEnum", Set.of(SchemaComponent.DUMMY_ENUM), ".setRating(QueryHelper.makeEnumMap(_nit_filmInput.getE(),");
    }

    @Test
    @DisplayName("Record containing jOOQ record")
    void containingRecords() {
        assertGeneratedContentContains(
                "containingRecords",
                "customerRecord = new ArrayList<CustomerRecord>();return _mlo_customerRecord"
        );
    }

    @Test
    @DisplayName("With scalar array field")
    void arrayScalarField() {
        assertGeneratedContentContains(
                "arrayScalarField",
                ".setUsernames(_nit_customer.getUsernames().stream().toArray(String[]::new));"
        );
    }
}
