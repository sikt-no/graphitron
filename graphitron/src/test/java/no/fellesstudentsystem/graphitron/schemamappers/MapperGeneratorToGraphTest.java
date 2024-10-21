package no.fellesstudentsystem.graphitron.schemamappers;

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

import static no.fellesstudentsystem.graphitron.common.configuration.ReferencedEntry.MAPPER_ID_SERVICE;

@DisplayName("JOOQ Mappers - Mapper content for mapping fields to graph types without records")
public class MapperGeneratorToGraphTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "schemamappers";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(MAPPER_ID_SERVICE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Default case with simple fields")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Default case with listed fields")
    void listedFields() {
        assertGeneratedContentContains(
                "listedFields",
                "Response0 recordToGraphType(List<String> response0Record,",
                "Response1 recordToGraphType(List<String> response1Record,"
        );
    }
}
