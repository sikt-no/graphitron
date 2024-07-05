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

@DisplayName("Test that jOOQ mappers are found and generated for conditions and service inputs")
public class MapperGeneratorToRecordClassesTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "jooqmappers/torecord";

    public MapperGeneratorToRecordClassesTest() {
        super(SRC_TEST_RESOURCES_PATH, List.of(MAPPER_DUMMY_SERVICE.get(), MAPPER_DUMMY_RECORD.get(), MAPPER_DUMMY_CONDITION.get()));
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, true));
    }

    @Test
    @DisplayName("Mapper content for fetch conditions")
    void forQueryWithConditionClasses() { // TODO: Cases where query has condition but should not make records.
        assertFilesAreGenerated(
                Set.of(
                        "AddressInputJOOQMapper.java",
                        "CityInput2JOOQMapper.java",
                        "CityInput3JOOQMapper.java"
                ),
                "forQueryWithConditionClasses"
        );
    }

    @Test
    @DisplayName("Mapper content for fetch service inputs")
    void forQueryWithServiceInputClasses() {
        assertFilesAreGenerated(
                Set.of(
                        "AddressInputJOOQMapper.java",
                        "CityInput2JOOQMapper.java",
                        "CityInput3JOOQMapper.java"
                ),
                "forQueryWithServiceInputClasses"
        );
    }
}
