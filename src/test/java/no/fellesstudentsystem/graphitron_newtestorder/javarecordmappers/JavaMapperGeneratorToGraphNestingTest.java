package no.fellesstudentsystem.graphitron_newtestorder.javarecordmappers;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.dummygenerators.DummyTransformerClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.*;

// This is split here so the dummy transformer is not included in other tests.
@DisplayName("Java Mappers - Mapper containing additional records")
public class JavaMapperGeneratorToGraphNestingTest extends GeneratorTest {
    public JavaMapperGeneratorToGraphNestingTest() {
        super(
                JavaMapperGeneratorToGraphTest.SRC_TEST_RESOURCES_PATH,
                DUMMY_SERVICE.get(),
                MAPPER_RECORD_ADDRESS.get(),
                MAPPER_RECORD_CITY.get()
        );
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, false), new DummyTransformerClassGenerator(schema));
    }

    @Test
    @DisplayName("Java record containing other records")
    void containingRecords() {
        assertGeneratedContentMatches("containingRecords");
    }
}
