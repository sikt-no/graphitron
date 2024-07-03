package no.fellesstudentsystem.graphitron_newtestorder.javarecordmappers;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

@DisplayName("Java Mappers - Mapper content for mapping graph types to Java records")
public class JavaMapperGeneratorToRecordTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "javamappers/tograph";

    public JavaMapperGeneratorToRecordTest() {
        super(SRC_TEST_RESOURCES_PATH, List.of());
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, true));
    }
}
