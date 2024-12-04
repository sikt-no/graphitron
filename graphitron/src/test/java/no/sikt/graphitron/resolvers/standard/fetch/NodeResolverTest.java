package no.sikt.graphitron.resolvers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;

@DisplayName("Interface resolvers - Resolvers for the Node interface")
public class NodeResolverTest extends GeneratorTest {

    @Override
    protected String getSubpath() {
        return "resolvers/fetch/interfaces/node";
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
    @DisplayName("No types implement Node interface")
    void nodeNoImplementations() {
        assertGeneratedContentMatches("noImplementations");
    }

    @Test
    @DisplayName("One type implement Node interface")
    void nodeOneImplementation() {
        assertGeneratedContentMatches("oneImplementation");
    }

    @Test
    @DisplayName("Many types implement Node interface")
    void manyImplementations() {
        assertGeneratedContentContains(
                "manyImplementations",
                "ADDRESS.getName(), \"Address\"",
                "CUSTOMER.getName(), \"Customer\"",
                "FILM.getName(), \"Film\"",
                "case \"Address\":",
                "case \"Customer\":",
                "case \"Film\":",
                "AddressDBQueries.loadAddressByIdsAsNode(",
                "CustomerDBQueries.loadCustomerByIdsAsNode(",
                "FilmDBQueries.loadFilmByIdsAsNode("
        );
    }

    @Test
    @DisplayName("Implementing type has no path from Query")
    void withoutPathFromQuery() {
        assertGeneratedContentContains("withoutPathFromQuery", "CustomerDBQueries.loadCustomerByIdsAsNode(");
    }
}
