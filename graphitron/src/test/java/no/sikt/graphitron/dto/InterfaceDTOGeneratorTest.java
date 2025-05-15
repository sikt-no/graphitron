package no.sikt.graphitron.dto;

import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.dto.InterfaceDTOGenerator;
import no.sikt.graphitron.generators.dto.TypeDTOGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

public class InterfaceDTOGeneratorTest extends DTOGeneratorTest {
    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new InterfaceDTOGenerator(schema), new TypeDTOGenerator(schema));
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
    @DisplayName("Single table interface")
    void singleTable() {
        assertGeneratedContentMatches("singleTable");
    }

    @Test
    @DisplayName("Interface with a type having notGenerated set on interface field")
    void withNotGeneratedField() {
        assertGeneratedContentContains("withNotGeneratedField",
                "public interface SomeInterface { }"
        );
    }
}
