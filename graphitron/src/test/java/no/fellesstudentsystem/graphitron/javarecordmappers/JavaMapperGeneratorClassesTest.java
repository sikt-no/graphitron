package no.fellesstudentsystem.graphitron.javarecordmappers;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.ReferencedEntry.DUMMY_RECORD;
import static no.fellesstudentsystem.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

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
        assertNothingGenerated("ignoresJOOQRecordClasses", Set.of(CUSTOMER_TABLE, CUSTOMER_INPUT_TABLE));
    }
}
