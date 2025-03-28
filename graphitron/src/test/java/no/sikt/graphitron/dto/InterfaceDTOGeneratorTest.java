package no.sikt.graphitron.dto;

import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.dto.InterfaceDTOGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

public class InterfaceDTOGeneratorTest extends DTOGeneratorTest {
    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new InterfaceDTOGenerator(schema));
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "interface";
    }

    @Test
    @DisplayName("Simple interface")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Interface with splitquery field")
    void splitQuery() {
        assertGeneratedContentMatches("splitQuery");
    }
}
