package no.fellesstudentsystem.graphitron_newtestorder.jooqrecordmappers;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.RecordValidation;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.dummygenerators.ReducedRecordMapperClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.DUMMY_SERVICE;

@DisplayName("JOOQ Validators - Validate mapped jOOQ records")
public class MapperGeneratorToRecordValidationTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "jooqmappers/validation";

    public MapperGeneratorToRecordValidationTest() {
        super(SRC_TEST_RESOURCES_PATH, DUMMY_SERVICE.get());
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
    @DisplayName("Default case with simple record mapper")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("jOOQ record containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentMatches("containingNonRecordWrapper");
    }

    @Test
    @DisplayName("jOOQ record containing non-record type and using field overrides")
    // This does not inherit @field the same way the mappers do, and may therefore have unwanted deviations.
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentMatches("containingNonRecordWrapperWithFieldOverride");
    }

    @Test
    @DisplayName("Handles fields that are not mapped to a record field") // TODO: This currently does not check for field existence.
    void unconfiguredField() {
        assertGeneratedContentMatches("unconfiguredField");
    }

    @Test
    @DisplayName("jOOQ record containing jOOQ record") // Can not do anything with records, so will skip them.
    void containingRecords() {
        assertGeneratedContentMatches("containingRecords");
    }
}
