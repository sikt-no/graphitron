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

@DisplayName("Test that Java mappers are found and generated for conditions and service inputs")
public class JavaMapperGeneratorToRecordClassesTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "javamappers/torecord";

    public JavaMapperGeneratorToRecordClassesTest() {
        super(SRC_TEST_RESOURCES_PATH, List.of(DUMMY_SERVICE.get(), DUMMY_RECORD.get(), DUMMY_CONDITION.get()));
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, true));
    }

    @Test
    @DisplayName("Mapper classes for fetch conditions")
    void forQueryWithConditionClasses() {
        assertFilesAreGenerated(
                Set.of(
                        "AddressInputJavaMapper.java",
                        "CityInput2JavaMapper.java",
                        "CityInput3JavaMapper.java",
                        "CityInput4JavaMapper.java"
                ),
                "forQueryWithConditionClasses"
        );
    }

    @Test
    @DisplayName("No mappers classes when there are no records")
    void forQueryWithConditionWithoutRecordClasses() {
        assertFilesAreGenerated(
                Set.of(),
                "forQueryWithConditionWithoutRecordClasses"
        );
    }

    @Test
    @DisplayName("Mapper classes for fetch service inputs")
    void forQueryWithServiceInputClasses() {
        assertFilesAreGenerated(
                Set.of(
                        "AddressInputJavaMapper.java",
                        "CityInput2JavaMapper.java",
                        "CityInput3JavaMapper.java",
                        "CityInput4JavaMapper.java"
                ),
                "forQueryWithServiceInputClasses"
        );
    }
}
