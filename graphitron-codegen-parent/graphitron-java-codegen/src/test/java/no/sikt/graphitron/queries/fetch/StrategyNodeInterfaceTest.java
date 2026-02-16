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
        assertGeneratedContentContains("regularIdField", "customer.CUSTOMER_ID");
    }

    @Test
    @DisplayName("Input ID field which is not node ID")
    void regularIdInputField() {
        assertGeneratedContentContains("regularIdInputField", Set.of(CUSTOMER_TABLE),
                "customer.CUSTOMER_ID.eq(_mi_id)"
        );
    }

    @Test
    @DisplayName("Querying a list of regular ID fields after mutation")
    void listedIdFieldAfterMutation() {
        assertGeneratedContentContains("listedIdFieldAfterMutation", Set.of(CUSTOMER_NODE),
                "DSL.multiset(DSL.select(_a_customer.CUSTOMER_ID)"
        );
    }

    @Test
    @DisplayName("Type implements node and non-node interface")
    void twoInterfaces() {
        assertGeneratedContentContains(
                "twoInterfaces",
                "customerForNode",
                ",Set<String> _mi_id,",
                ".where(_iv_nodeIdStrategy.hasIds(\"Customer\", _mi_id, _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))"
        );
    }

    @Test
    @Disabled("Disabled until alwaysUsePrimaryKeyInSplitQueries-property is removed.")
    @DisplayName("Split query")
    void splitQuery() {
        assertGeneratedContentContains("splitQuery",
                "nodeIdStrategy.createId(\"Customer\", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))," +
                        "DSL.field(",
                ".where(_iv_nodeIdStrategy.hasIds(\"Customer\", _mi_customerIds, _a_customer.fields"
        );
    }

    @Test
    @DisplayName("Split query with primary key")
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
                "countQueryForQuery(DSLContext _iv_ctx, NodeIdStrategy _iv_nodeIdStrategy)"
        );
    }

    @Test
    @DisplayName("Multitable query")
    void multitable() {
        assertGeneratedContentContains(
                "multitable", Set.of(CUSTOMER_TABLE),
                "customertableForQuery(_iv_nodeIdStrategy)",
                "customertableForQuery(NodeIdStrategy _iv_nodeIdStrategy)",
                "customertableSortFieldsForQuery(_iv_nodeIdStrategy)"
        );
    }

    @Test
    @DisplayName("Multitable query with input")
    void multitableWithInput() {
        assertGeneratedContentContains(
                "multitableWithInput", Set.of(CUSTOMER_TABLE),
                "customertableSortFieldsForQuery(NodeIdStrategy _iv_nodeIdStrategy, Integer _mi_customerId)",
                "customertableSortFieldsForQuery(_iv_nodeIdStrategy, _mi_customerId)"
        );
    }
}
