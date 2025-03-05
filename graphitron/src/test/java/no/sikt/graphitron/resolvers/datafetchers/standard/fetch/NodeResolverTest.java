package no.sikt.graphitron.resolvers.datafetchers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.FetchClassGenerator;
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
        return "resolvers/datafetchers/fetch/node";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchClassGenerator(schema));
    }

    @Test
    @DisplayName("No types implement Node interface")
    void nodeNoImplementations() {
        assertGeneratedContentMatches("noImplementations");
    }

    @Test
    @DisplayName("One type implements Node interface")
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
                "AddressDBQueries.addressForNode(",
                "CustomerDBQueries.customerForNode(",
                "FilmDBQueries.filmForNode("
        );
    }

    @Test
    @DisplayName("Implementing type has no path from Query")
    void withoutPathFromQuery() {
        assertGeneratedContentContains("withoutPathFromQuery", "CustomerDBQueries.customerForNode(");
    }

    @Test
    @DisplayName("Type implements interface and Node")
    void doubleInterface() {
        assertGeneratedContentContains(
                "doubleInterface", Set.of(NODE),
                "FilmDBQueries.filmForNode(",
                "QueryDBQueries.titledForQuery("
        );
    }
}
