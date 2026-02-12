package no.sikt.graphitron.datafetchers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Interface resolvers - Resolvers for the Node strategy interface")
public class StrategyNodeResolverTest extends GeneratorTest {

    // Disabled until GGG-104
    @Override
    protected boolean validateSchema() {
        return false;
    }

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
        return "datafetchers/fetch/strategynode";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
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
    @DisplayName("Custom type id generates correct switch statements")
    void nodeCorrectSwitchStatement() {
        assertGeneratedContentContains("withCustomTypeId", "case \"CustomerType\"");
    }

    @Test
    @DisplayName("Many types implement Node interface")
    void manyImplementations() {
        assertGeneratedContentContains(
                "manyImplementations",
                "case \"Address\":",
                "case \"Customer\":",
                "AddressDBQueries.addressForNode(",
                "case \"Film\":",
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
                "_entities(NodeIdStrategy _iv_nodeIdStrategy)",
                "_entitiesForQuery(_iv_ctx, _iv_nodeIdStrategy, _mi_representations"
        );
    }

    @Test
    @DisplayName("Connection")
    void connection() {
        assertGeneratedContentContains(
                "connection", Set.of(CUSTOMER_CONNECTION),
                ".queryForQuery(_iv_ctx, _iv_nodeIdStrategy,",
                ".countQueryForQuery(_iv_ctx, _iv_nodeIdStrategy)"
        );
    }
}
