package no.fellesstudentsystem.graphitron_newtestorder.recordmappers;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.*;

@DisplayName("JOOQ Mappers - Mapper content for mapping jOOQ records to graph types")
public class MapperGeneratorToGraphTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "jooqmappers/tograph";

    public MapperGeneratorToGraphTest() {
        super(SRC_TEST_RESOURCES_PATH, Set.of(DUMMY_SERVICE.get(), DUMMY_RECORD.get(), DUMMY_ENUM.get(), MAPPER_FETCH_SERVICE.get()));
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Mapper content for fetch services")
    void forFetchService() {
        assertGeneratedContentMatches("forFetchService");
    }

    @Test
    @DisplayName("Mapper content for records with enums")
    void withEnum() {
        assertGeneratedContentMatches("withEnum");
    }
}
