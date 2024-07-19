package no.fellesstudentsystem.graphitron_newtestorder.jooqrecordmappers;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.*;

@DisplayName("JOOQ Mappers - Mapper content for mapping jOOQ records to graph types")
public class MapperGeneratorToGraphTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "jooqmappers/tograph";

    public MapperGeneratorToGraphTest() {
        super(SRC_TEST_RESOURCES_PATH, DUMMY_SERVICE.get(), DUMMY_RECORD.get(), DUMMY_ENUM.get(), MAPPER_FETCH_SERVICE.get());
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Default case with simple record mapper")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Mapper with a non-record outer wrapper")
    void withNonRecordWrapper() {
        assertGeneratedContentMatches("withNonRecordWrapper");
    }

    @Test
    @DisplayName("jOOQ record containing jOOQ record")
    void containingRecord() {
        assertGeneratedContentMatches("containingRecord");
    }

    @Test
    @DisplayName("jOOQ record containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentMatches("containingNonRecordWrapper");
    }

    @Test
    @DisplayName("Records with enum fields")
    void withEnum() {
        assertGeneratedContentMatches("withEnum");
    }
}
