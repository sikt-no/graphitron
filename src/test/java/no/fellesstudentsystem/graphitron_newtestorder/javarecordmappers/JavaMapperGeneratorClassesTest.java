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

@DisplayName("Java Mappers - Mapper classes for mapping Java records")
public class JavaMapperGeneratorClassesTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "javamappers";

    public JavaMapperGeneratorClassesTest() {
        super(SRC_TEST_RESOURCES_PATH, DUMMY_SERVICE.get(), DUMMY_RECORD.get());
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Mapper generator ignores JOOQ records")
    void ignoresJOOQRecordClasses() {
        assertFilesAreGenerated("ignoresJOOQRecordClasses");
    }
}
