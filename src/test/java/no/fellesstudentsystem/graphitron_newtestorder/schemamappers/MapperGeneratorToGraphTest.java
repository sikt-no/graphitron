package no.fellesstudentsystem.graphitron_newtestorder.schemamappers;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.MAPPER_ID_SERVICE;

@DisplayName("JOOQ Mappers - Mapper content for mapping fields to graph types without records")
public class MapperGeneratorToGraphTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "schemamappers";

    public MapperGeneratorToGraphTest() {
        super(SRC_TEST_RESOURCES_PATH, MAPPER_ID_SERVICE.get());
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Default case with simple fields")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Default case with listed fields")
    void listedFields() {
        assertGeneratedContentMatches("listedFields");
    }
}
