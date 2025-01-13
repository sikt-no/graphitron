package no.sikt.graphitron.resolvers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;

@DisplayName("Interface resolvers - Resolvers for interfaces other than Node")
public class InterfaceResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/interfaces/standard";
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("One implementation")
    void oneImplementation() {
        assertGeneratedContentMatches(
                "oneImplementation"
        );
    }

    @Test
    @DisplayName("No implementations")
    void noImplementations() {
        assertGeneratedContentContains("noImplementations",
                "QueryDBQueries.titledForQuery(");
    }

    @Test
    @DisplayName("Many implementations")
    void manyImplementations() {
        assertGeneratedContentContains(
                "manyImplementations",
                "QueryDBQueries.titledForQuery("
        );
    }

    @Test
    @DisplayName("Returning list")
    void returningList() {
        assertGeneratedContentContains("returningList",
                "CompletableFuture<List<Titled>>",
                "QueryDBQueries.titledForQuery("
        );
    }

    @Test
    @DisplayName("Type implements interface and Node")
    void doubleInterface() {
        assertGeneratedContentContains(
                "doubleInterface", Set.of(NODE),
                "FilmDBQueries.loadFilmByIdsAsNode(",
                "QueryDBQueries.titledForQuery("
        );
    }

    @Test
    @DisplayName("Paginated")
    void paginated() {
        assertGeneratedContentMatches(
                "paginated"
        );
    }

    @Test
    @DisplayName("On field returning discriminating interface")
    void withInput() {
        assertGeneratedContentContains("withInput",
                "address(AddressInput filter,",
                "addressForQuery(ctx, filter, selectionSet)"
        );
    }
}
