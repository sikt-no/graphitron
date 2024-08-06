package no.fellesstudentsystem.graphitron_newtestorder.jooqrecordmappers;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.RecordValidation;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.reducedgenerators.ReducedRecordMapperClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.DUMMY_SERVICE;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.CUSTOMER_INPUT_TABLE;

@DisplayName("JOOQ Validators - Validate mapped jOOQ records")
public class MapperGeneratorToRecordValidationTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "jooqmappers";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new ReducedRecordMapperClassGenerator(schema)); // Generates only validation.
    }

    @BeforeEach
    void before() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
    }

    @Test
    @DisplayName("Simple mapper with one field")
    void defaultCase() {
        assertGeneratedContentMatches("validation/default", CUSTOMER_INPUT_TABLE);
    }

    @Test
    @DisplayName("Field using the @field directive")
    void mappedField() {
        assertGeneratedContentContains(
                "torecord/mappedField",
                "pathHere + \"first\"",
                ".put(\"firstName\", pathHere + itCustomerRecordListIndex + \"/first\""
        );
    }

    @Test
    @DisplayName("Mapper with multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "torecord/twoFields",
                "pathHere + \"id\"",
                ".put(\"id\", pathHere + itCustomerRecordListIndex + \"/id\"",
                "pathHere + \"first\"",
                ".put(\"firstName\", pathHere + itCustomerRecordListIndex + \"/first\""
        );
    }

    @Test
    @DisplayName("Record containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentContains(
                "torecord/containingNonRecordWrapper",
                ".put(\"postalCode\", pathHere + itAddressRecordListIndex + \"/inner/postalCode\""
        );
    }

    @Test
    @DisplayName("Mapper with two layers of non-record types")
    void containingDoubleNonRecordWrapper() {
        assertGeneratedContentContains(
                "torecord/containingDoubleNonRecordWrapper",
                ".put(\"postalCode\", pathHere + itAddressRecordListIndex + \"/inner0/inner1/postalCode\""
        );
    }

    @Test
    @DisplayName("Fields on different levels that have the same name")
    void nestingWithDuplicateFieldName() {
        assertGeneratedContentContains(
                "torecord/nestingWithDuplicateFieldName",
                ".put(\"postalCode\", pathHere + itAddressRecordListIndex + \"/inner/inner/postalCode\""
        );
    }

    @Test
    @DisplayName("Record containing non-record type and using field overrides")
    // This does not inherit @field the same way the mappers do, and may therefore have unwanted deviations.
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentContains(
                "torecord/containingNonRecordWrapperWithFieldOverride",
                "pathHere + \"inner1/code\"",
                ".put(\"postalCode\", pathHere + itAddressRecordListIndex + \"/inner1/code\"",
                "pathHere + \"inner2/code\"",
                ".put(\"code\", pathHere + itAddressRecordListIndex + \"/inner2/code\""
        );
    }

    @Test
    @DisplayName("Handles fields that are not mapped to a record field") // TODO: This currently does not check for field existence.
    void unconfiguredField() {
        assertGeneratedContentContains(
                "torecord/unconfiguredField",
                ".put(\"id1\", pathHere + itCustomerRecordListIndex + \"/id1\")",
                ".put(\"wrongName\", pathHere + itCustomerRecordListIndex + \"/id2\""
        );
    }

    @Test
    @DisplayName("Record containing jOOQ record") // Can not do anything with jOOQ records, so will skip them.
    void containingRecords() {
        assertGeneratedContentContains(
                "torecord/containingRecords",
                "validationErrors = new HashSet<GraphQLError>();return validationErrors"
        );
    }
}
