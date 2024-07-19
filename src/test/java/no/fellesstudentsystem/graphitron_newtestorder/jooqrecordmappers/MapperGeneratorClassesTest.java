package no.fellesstudentsystem.graphitron_newtestorder.jooqrecordmappers;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.DUMMY_RECORD;
import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.DUMMY_SERVICE;

@DisplayName("JOOQ Mappers - Mapper classes for mapping jOOQ records")
public class MapperGeneratorClassesTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "jooqmappers";

    public MapperGeneratorClassesTest() {
        super(SRC_TEST_RESOURCES_PATH, DUMMY_SERVICE.get(), DUMMY_RECORD.get());
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Mapper generator ignores Java records")
    void ignoresJavaRecordClasses() {
        assertFilesAreGenerated("ignoresJavaRecordClasses");
    }
}
