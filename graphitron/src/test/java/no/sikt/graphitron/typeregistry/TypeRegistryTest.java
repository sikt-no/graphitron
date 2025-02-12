package no.sikt.graphitron.typeregistry;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.TypeRegistryClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_QUERY;

@DisplayName("Type Registry - Generation of the method returning a type registry")
public class TypeRegistryTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "typeregistry";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new TypeRegistryClassGenerator());
    }

    @Test
    @DisplayName("Return type registry")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_QUERY, CUSTOMER);
    }
}
