package no.fellesstudentsystem.graphitron_newtestorder.javarecordmappers;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.*;

@DisplayName("Java Mappers - Mapper content for mapping graph types to Java records")
public class JavaMapperGeneratorToRecordTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "javamappers/torecord";

    public JavaMapperGeneratorToRecordTest() {
        super(SRC_TEST_RESOURCES_PATH, Set.of(DUMMY_SERVICE.get(), DUMMY_ENUM.get(), MAPPER_RECORD_FILM.get()));
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, true));
    }

    @Test
    @DisplayName("Mapper content for records with enums")
    void withEnum() {
        assertGeneratedContentMatches("withEnum");
    }
}
