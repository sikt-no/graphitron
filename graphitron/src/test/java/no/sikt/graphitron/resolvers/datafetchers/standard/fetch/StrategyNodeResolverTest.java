package no.sikt.graphitron.resolvers.datafetchers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.EntityFetcherClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.FetchClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION_QUERY;
import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;

@DisplayName("Interface resolvers - Resolvers for the Node strategy interface")
public class StrategyNodeResolverTest extends GeneratorTest {
    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @Override
    protected String getSubpath() {
        return "resolvers/datafetchers/fetch/strategynode";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchClassGenerator(schema), new EntityFetcherClassGenerator(schema));
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
                "case \"ADDRESS\":",
                "case \"CUSTOMER\":",
                "AddressDBQueries.addressForNode(",
                "case \"FILM\":",
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

    @Test
    @DisplayName("Entity type")
    void entity() {
        assertGeneratedContentContains(
                "entity", Set.of(NODE, FEDERATION_QUERY),
                "entityFetcher(NodeIdStrategy nodeIdStrategy)",
                "customerAsEntity(ctx, internal_it_, nodeIdStrategy)"
        );
    }
}
