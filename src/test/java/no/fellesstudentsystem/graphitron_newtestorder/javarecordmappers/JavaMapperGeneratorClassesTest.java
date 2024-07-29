package no.fellesstudentsystem.graphitron_newtestorder.javarecordmappers;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.DUMMY_RECORD;
import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.DUMMY_SERVICE;

@DisplayName("Java Mappers - Mapper classes for mapping Java records")
public class JavaMapperGeneratorClassesTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "javamappers";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, DUMMY_RECORD);
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
