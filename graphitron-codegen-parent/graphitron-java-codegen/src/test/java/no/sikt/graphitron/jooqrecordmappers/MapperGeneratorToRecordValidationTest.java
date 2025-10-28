package no.sikt.graphitron.jooqrecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.ReducedRecordMapperClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;

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
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new ReducedRecordMapperClassGenerator(schema)); // Generates only validation.
    }

    @BeforeEach
    public void setup() {
        super.setup();
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
                ".put(\"firstName\", _iv_pathHere + _niit_customerRecord + \"/first\""
        );
    }

    @Test
    @DisplayName("Mapper with multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "torecord/twoFields",
                "pathHere + \"id\"",
                ".put(\"id\", _iv_pathHere + _niit_customerRecord + \"/id\"",
                "pathHere + \"first\"",
                ".put(\"firstName\", _iv_pathHere + _niit_customerRecord + \"/first\""
        );
    }

    @Test
    @DisplayName("Record containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentContains(
                "torecord/containingNonRecordWrapper",
                ".put(\"postalCode\", _iv_pathHere + _niit_addressRecord + \"/inner/postalCode\""
        );
    }

    @Test
    @DisplayName("Mapper with two layers of non-record types")
    void containingDoubleNonRecordWrapper() {
        assertGeneratedContentContains(
                "torecord/containingDoubleNonRecordWrapper",
                ".put(\"postalCode\", _iv_pathHere + _niit_addressRecord + \"/inner0/inner1/postalCode\""
        );
    }

    @Test
    @DisplayName("Fields on different levels that have the same name")
    void nestingWithDuplicateFieldName() {
        assertGeneratedContentContains(
                "torecord/nestingWithDuplicateFieldName",
                ".put(\"postalCode\", _iv_pathHere + _niit_addressRecord + \"/inner/inner/postalCode\""
        );
    }

    @Test
    @DisplayName("Record containing non-record type and using field overrides")
    // This does not inherit @field the same way the mappers do, and may therefore have unwanted deviations.
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentContains(
                "torecord/containingNonRecordWrapperWithFieldOverride",
                "pathHere + \"inner1/code\"",
                ".put(\"postalCode\", _iv_pathHere + _niit_addressRecord + \"/inner1/code\"",
                "pathHere + \"inner2/code\"",
                ".put(\"code\", _iv_pathHere + _niit_addressRecord + \"/inner2/code\""
        );
    }

    @Test
    @DisplayName("Handles fields that are not mapped to a record field") // TODO: This currently does not check for field existence.
    void unconfiguredField() {
        assertGeneratedContentContains(
                "torecord/unconfiguredField",
                ".put(\"id1\", _iv_pathHere + _niit_customerRecord + \"/id1\")",
                ".put(\"wrongName\", _iv_pathHere + _niit_customerRecord + \"/id2\""
        );
    }

    @Test
    @DisplayName("Record containing jOOQ record") // Can not do anything with jOOQ records, so will skip them.
    void containingRecords() {
        assertGeneratedContentContains(
                "torecord/containingRecords",
                "validationErrors = new HashSet<GraphQLError>();return _iv_validationErrors"
        );
    }
}
