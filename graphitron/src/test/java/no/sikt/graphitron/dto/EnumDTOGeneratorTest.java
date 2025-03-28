package no.sikt.graphitron.dto;

import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.dto.EnumDTOGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM;

public class EnumDTOGeneratorTest extends DTOGeneratorTest {
    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new EnumDTOGenerator(schema));
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "enum";
    }

    @Test
    @DisplayName("Simple enum")
    void defaultCase() {
        assertGeneratedContentContains("default", Set.of(DUMMY_ENUM),
                "public enum DummyEnum { A, B, C }"
        );
    }
}
