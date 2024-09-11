package no.fellesstudentsystem.graphitron.jooqrecordmappers;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.DUMMY_INPUT_RECORD;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.DUMMY_TYPE_RECORD;

@DisplayName("JOOQ Mappers - Mapper classes for mapping jOOQ records")
public class MapperGeneratorClassesTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "jooqmappers";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Mapper generator ignores Java records")
    void ignoresJavaRecordClasses() {
        assertNothingGenerated("ignoresJavaRecordClasses", Set.of(DUMMY_TYPE_RECORD, DUMMY_INPUT_RECORD));
    }
}
