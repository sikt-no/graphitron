package no.sikt.graphitron.dto;

import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.dto.UnionDTOGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

public class UnionDTOGeneratorTest extends DTOGeneratorTest {
    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new UnionDTOGenerator(schema));
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "union";
    }

    @Test
    @DisplayName("Union")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_TABLE);
    }
}
