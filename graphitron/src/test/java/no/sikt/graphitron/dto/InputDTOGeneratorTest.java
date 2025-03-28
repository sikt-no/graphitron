package no.sikt.graphitron.dto;

import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.dto.InputDTOGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

public class InputDTOGeneratorTest extends DTOGeneratorTest {
    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new InputDTOGenerator(schema));
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "input";
    }

    @Test
    @DisplayName("Input type")
    void defaultCase() {
        assertGeneratedContentContains("default", Set.of(DUMMY_INPUT),
                "DummyInput(String id)");
    }
}
