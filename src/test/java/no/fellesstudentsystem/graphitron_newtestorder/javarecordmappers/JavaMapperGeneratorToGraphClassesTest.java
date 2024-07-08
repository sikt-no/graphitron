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

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.DUMMY_RECORD;
import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.DUMMY_SERVICE;

@DisplayName("Java Mappers - Mappers classes for mapping Java records to graph types")
public class JavaMapperGeneratorToGraphClassesTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "javamappers/tograph";

    public JavaMapperGeneratorToGraphClassesTest() {
        super(SRC_TEST_RESOURCES_PATH, List.of(DUMMY_SERVICE.get(), DUMMY_RECORD.get()));
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Mapper classes for fetch services")
    void forFetchServiceClasses() {
        assertFilesAreGenerated(
                Set.of(
                        "AddressCity1TypeMapper.java",
                        "AddressTypeMapper.java",
                        "AddressWrappedTypeMapper.java",
                        "CustomerTypeMapper.java"
                ),
                "forFetchServiceClasses"
        );
    }

    @Test
    @DisplayName("Mapper classes for fetch services with connections")
    void forFetchServiceConnectionClasses() {
        assertFilesAreGenerated(
                Set.of(
                        "AddressTypeMapper.java",
                        "CustomerTypeMapper.java",
                        "RecordCityTypeMapper.java"
                ),
                "forFetchServiceConnectionClasses"
        );
    }
}
