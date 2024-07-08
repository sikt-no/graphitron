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

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.DUMMY_RECORD;
import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.DUMMY_SERVICE;

@DisplayName("JOOQ Mappers - Mappers classes for mapping jOOQ records to graph types")
public class MapperGeneratorToGraphClassesTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "jooqmappers/tograph";

    public MapperGeneratorToGraphClassesTest() {
        super(SRC_TEST_RESOURCES_PATH, List.of(DUMMY_SERVICE.get(), DUMMY_RECORD.get()));
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Mapper classes for fetch services")
    void forFetchServiceClasses() {
        assertFilesAreGenerated(
                Set.of(
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
                        "CityTypeMapper.java",
                        "CustomerTypeMapper.java"
                ),
                "forFetchServiceConnectionClasses"
        );
    }
}
