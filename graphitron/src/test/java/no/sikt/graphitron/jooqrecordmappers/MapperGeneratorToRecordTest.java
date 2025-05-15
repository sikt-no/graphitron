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
                "customerRecord.setFirstName(itCustomer.getFirst()"
        );
    }

    @Test
    @DisplayName("Mapper with multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                "pathHere + \"id\"",
                "customerRecord.setId(itCustomer.getId()",
                "pathHere + \"first\"",
                "customerRecord.setFirstName(itCustomer.getFirst()"
        );
    }

    @Test
    @DisplayName("Record containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingNonRecordWrapper",
                "if (address_inner != null) {" +
                        "    if (_args.contains(pathHere + \"inner/postalCode\")) {" +
                        "        addressRecord.setPostalCode(address_inner.getPostalCode());" +
                        "    }" +
                        "}" +
                        "addressRecordList.add(addressRecord)"
        );
    }

    @Test
    @DisplayName("Mapper with two layers of non-record types")
    void containingDoubleNonRecordWrapper() {
        assertGeneratedContentContains(
                "containingDoubleNonRecordWrapper",
                "address_inner0 = itAddress.getInner0();" +
                        "if (address_inner0 != null) {" +
                        "    var wrapper_inner1 = address_inner0.getInner1();" +
                        "    if (wrapper_inner1 != null) {" +
                        "        if (_args.contains(pathHere + \"inner0/inner1/postalCode\")) {" +
                        "            addressRecord.setPostalCode(wrapper_inner1.getPostalCode());"
        );
    }

    @Test
    @DisplayName("Fields on different levels that have the same name")
    void nestingWithDuplicateFieldName() {
        assertGeneratedContentContains(
                "nestingWithDuplicateFieldName",
                "address_inner = itAddress.getInner();" +
                        "if (address_inner != null) {" +
                        "    var wrapper_inner = address_inner.getInner();" +
                        "    if (wrapper_inner != null) {" +
                        "        if (_args.contains(pathHere + \"inner/inner/postalCode\")) {" +
                        "            addressRecord.setPostalCode(wrapper_inner.getPostalCode()"
        );
    }

    @Test
    @DisplayName("Record containing non-record type and using field overrides")
    @Disabled("This confuses the temporary variable a bit, but otherwise works.")
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentContains(
                "containingNonRecordWrapperWithFieldOverride",
                "address_inner1 = itAddress.getInner1()",
                ".setPostalCode(address_inner1.getCode()",
                "address_inner2 = itAddress.getInner2()",
                ".setPostalCode(address_inner2.getCode()"
        );
    }

    @Test
    @DisplayName("Handles fields that are not mapped to a record field") // TODO: This currently produces illegal code.
    void unconfiguredField() {
        assertGeneratedContentContains(
                "unconfiguredField",
                ".setId1(itCustomer.getId1()",
                ".setWrongName(itCustomer.getId2()"
        );
    }

    @Test
    @DisplayName("Maps ID fields that are not the primary key")
    void idOtherThanPK() {
        assertGeneratedContentContains("idOtherThanPK", ".setAddressId(itCustomer.getAddressId()");
    }

    @Test
    @DisplayName("Records with enum fields")
    void withEnum() {
        assertGeneratedContentContains("withEnum", Set.of(SchemaComponent.DUMMY_ENUM), ".setRating(QueryHelper.makeEnumMap(itFilmInput.getE(),");
    }

    @Test
    @DisplayName("Record containing jOOQ record")
    void containingRecords() {
        assertGeneratedContentContains(
                "containingRecords",
                "customerRecordList = new ArrayList<CustomerRecord>();return customerRecordList"
        );
    }
}
