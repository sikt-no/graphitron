package no.fellesstudentsystem.graphitron_newtestorder.javarecordmappers;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.*;

@DisplayName("Java Mappers - Mapper content for mapping Java records to graph types")
public class JavaMapperGeneratorToGraphTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "javamappers/tograph";

    public JavaMapperGeneratorToGraphTest() {
        super(SRC_TEST_RESOURCES_PATH, List.of(MAPPER_DUMMY_SERVICE.get(), MAPPER_RECORD_CUSTOMER.get(), MAPPER_RECORD_ADDRESS.get(), MAPPER_RECORD_CITY.get()));
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Java mapper content for fetch services")
    void forFetchServiceContent() {
        assertGeneratedContentMatches("forFetchServiceContent");
    }
}
