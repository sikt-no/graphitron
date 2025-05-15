package no.sikt.graphitron.resolvers.type;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.typeresolvers.TypeResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION_QUERY;

@DisplayName("Type resolvers - Resolvers for unions and interfaces")
public class TypeResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/type";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new TypeResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("Union with one component")
    void unionType() {
        assertGeneratedContentMatches("union");
    }

    @Test
    @DisplayName("Interface with one implementation")
    void interfaceType() {
        assertGeneratedContentMatches("interface");
    }

    @Test
    @DisplayName("Union with two components")
    void unionTypeDouble() {
        assertGeneratedContentContains("unionDouble", "_obj instanceof A", "_obj instanceof B");
    }

    @Test
    @DisplayName("Interface with two implementations")
    void interfaceTypeDouble() {
        assertGeneratedContentContains("interfaceDouble", "_obj instanceof A", "_obj instanceof B");
    }

    @Test
    @DisplayName("Interface with no implementations")
    void interfaceNoImplementations() {
        assertGeneratedContentContains("interfaceNoImplementations", "Object _obj) {throw new");
    }

    @Test
    @DisplayName("Federation Entity union")
    void entity() {
        assertGeneratedContentMatches("entity", FEDERATION_QUERY);
    }
}
