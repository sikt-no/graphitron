package no.fellesstudentsystem.graphitron_newtestorder.resolvers.fetch;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.NODE;

@DisplayName("Interface resolvers - Resolvers for the interface")
public class InterfaceResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/interfaces";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE); // Node is used for these tests since resolver code should be the same for all interfaces.
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("No types implement interface")
    void noImplementations() {
        assertGeneratedContentMatches("noImplementations");
    }

    @Test
    @DisplayName("One type implement interface")
    void oneImplementation() {
        assertGeneratedContentMatches("oneImplementation");
    }

    @Test
    @DisplayName("Many types implement interface")
    void manyImplementations() {
        assertGeneratedContentMatches("manyImplementations");
    }

    @Test
    @DisplayName("Type implements two interfaces")
    void doubleInterface() {
        assertGeneratedContentMatches("doubleInterface");
    }

    @Test
    @DisplayName("Implementing type has no path from Query")
    void withoutPathFromQuery() {
        assertGeneratedContentMatches("withoutPathFromQuery");
    }
}
