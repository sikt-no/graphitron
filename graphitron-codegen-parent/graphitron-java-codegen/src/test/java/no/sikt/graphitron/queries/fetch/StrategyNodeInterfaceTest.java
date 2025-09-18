package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.NodeStrategyInterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Query node - Interface handling for types implementing node strategy interface")
public class StrategyNodeInterfaceTest extends GeneratorTest {

    // Diabled until GGG-104
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
        return "queries/fetch/interfaces/strategynode";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new NodeStrategyInterfaceOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Only ID")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("ID field which is not node ID")
    void regularIdField() {
        assertGeneratedContentContains("regularIdField", "_customer.CUSTOMER_ID");
    }

    @Test
    @DisplayName("Input ID field which is not node ID")
    void regularIdInputField() {
        assertGeneratedContentContains("regularIdInputField", Set.of(CUSTOMER_TABLE),
                "_customer.CUSTOMER_ID.eq(id)"
        );
    }

    @Test
    @DisplayName("Querying a list of regular ID fields after mutation")
    void listedIdFieldAfterMutation() {
        assertGeneratedContentContains("listedIdFieldAfterMutation", Set.of(CUSTOMER_NODE),
                "DSL.multiset(DSL.select(_customer.CUSTOMER_ID)"
        );
    }

    @Test
    @DisplayName("Multiple fields")
    void manyFields() {
        assertGeneratedContentContains("manyFields", "_customer.FIRST_NAME", "_customer.LAST_NAME");
    }

    @Test
    @DisplayName("Type implements node and non-node interface")
    void twoInterfaces() {
        assertGeneratedContentContains(
                "twoInterfaces",
                "customerForNode",
                ",Set<String> ids,",
                ".where(nodeIdStrategy.hasIds(\"Customer\", ids, _customer.fields(_customer.getPrimaryKey().getFieldsArray()))"
        );
    }

    @Test
    @Disabled("Disabled until alwaysUsePrimaryKeyInSplitQueries-property is removed.")
    @DisplayName("Split query")
    void splitQuery() {
        assertGeneratedContentContains("splitQuery",
                "nodeIdStrategy.createId(\"Customer\", _customer.fields(_customer.getPrimaryKey().getFieldsArray()))," +
                        "DSL.field(",
                ".where(nodeIdStrategy.hasIds(\"Customer\", customerIds, _customer.fields"
        );
    }

    @Test
    @DisplayName("Split query")
    void splitQueryOnlyPrimaryKey() {
        assertGeneratedContentMatches("splitQueryOnlyPrimaryKey");
    }

    @Test
    @DisplayName("Root query")
    void rootQuery() {
        assertGeneratedContentMatches("rootQuery");
    }

    @Test
    @DisplayName("Connection")
    void connection() {
        assertGeneratedContentContains(
                "connection", Set.of(CUSTOMER_CONNECTION),
                "countQueryForQuery(DSLContext ctx, NodeIdStrategy nodeIdStrategy)"
        );
    }

    @Test
    @DisplayName("Multitable query")
    void multitable() {
        assertGeneratedContentContains(
                "multitable", Set.of(CUSTOMER_TABLE),
                "customertableForQuery(nodeIdStrategy)",
                "customertableForQuery( NodeIdStrategy nodeIdStrategy)",
                "customertableSortFieldsForQuery()"
        );
    }
}
