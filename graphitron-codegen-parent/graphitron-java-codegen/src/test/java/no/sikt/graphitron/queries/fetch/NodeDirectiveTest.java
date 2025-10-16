package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.EntityFetchOnlyDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

public class NodeDirectiveTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/nodeDirective";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new InterfaceOnlyFetchDBClassGenerator(schema), new MapOnlyFetchDBClassGenerator(schema), new EntityFetchOnlyDBClassGenerator(schema));
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @Test
    @DisplayName("Default case")
    void defaultCase() {
        assertGeneratedContentContains(
                "root/default", Set.of(CUSTOMER_QUERY),
                """
                        public static Customer queryForQuery(DSLContext _iv_ctx, NodeIdStrategy _iv_nodeIdStrategy,
                                SelectionSet _iv_select) {
                            var _a_customer = CUSTOMER.as("customer_2168032777");
                            return _iv_ctx
                                    .select(queryForQuery_customer(_iv_nodeIdStrategy))
                                    .from(_a_customer)
                                    .fetchOne(_iv_it -> _iv_it.into(Customer.class));
                        }
                        private static SelectField<Customer> queryForQuery_customer(NodeIdStrategy _iv_nodeIdStrategy) {
                            var _a_customer = CUSTOMER.as("customer_2168032777");
                            return DSL.row(_iv_nodeIdStrategy.createId("Customer", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Customer::new));
                        }
                        """
        );
    }

    @Test
    @DisplayName("ID in nested query")
    void nested() {
        assertGeneratedContentContains(
                "root/nested", Set.of(CUSTOMER_QUERY),
                """
                            private static SelectField<Address> queryForQuery_customer_address(
                                    no.sikt.graphitron.jooq.generated.testdata.public_.tables.Address _a_address, NodeIdStrategy _iv_nodeIdStrategy) {
                                return DSL.row(_iv_nodeIdStrategy.createId("Address", _a_address.fields(_a_address.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Address::new));
                            }
                        """
        );
    }

    @Test
    @DisplayName("With ID using custom type ID")
    void customTypeId() {
        assertGeneratedContentContains(
                "root/customTypeId", Set.of(CUSTOMER_QUERY),
                ".createId(\"C\", _a_customer.fields("
        );
    }

    @Test
    @DisplayName("With ID using custom key columns")
    void customKeyColumns() {
        assertGeneratedContentContains(
                "root/customKeyColumns", Set.of(CUSTOMER_QUERY),
                ".createId(\"Customer\", _a_customer.CUSTOMER_ID, _a_customer.EMAIL)).mapping"
        );
    }

    @Test
    @DisplayName("With custom key columns with wrong case")
    void customKeyColumnsWrongCase() {
        assertGeneratedContentContains(
                "root/customKeyColumnsWrongCase", Set.of(CUSTOMER_QUERY),
                ".createId(\"Customer\", _a_customer.CUSTOMER_ID, _a_customer.EMAIL)).mapping"
        );
    }

    @Test
    @DisplayName("Default ID in splitQuery")
    void splitQuery() {
        assertGeneratedContentContains(
                "splitQuery/default",
                "DSL.row(_iv_nodeIdStrategy.createId(\"Customer\", _a_address_2"
        );
    }

    @Test
    @DisplayName("ID with custom type ID in splitQuery")
    void customTypeIdInSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/customTypeId",
                "DSL.row(_iv_nodeIdStrategy.createId(\"C\", _a_address_2"
        );
    }

    @Test
    @DisplayName("ID using custom key columns in splitQuery")
    void customKeyColumnsInSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/customKeyColumns",
                "DSL.row(_iv_nodeIdStrategy.createId(\"Address\", _a_customer_2168032777_address.ADDRESS_ID)).mapping"
        );
    }

    @Test
    @DisplayName("Default ID in Node query")
    void nodeQuery() {
        assertGeneratedContentContains(
                "nodeQuery/default", Set.of(NODE_QUERY),
                "nodeIdStrategy.createId(\"Customer\", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())), DSL.row(",
                ".where(_iv_nodeIdStrategy.hasIds(\"Customer\", id, _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())))"
        );
    }

    @Test
    @DisplayName("ID with custom type ID in Node query")
    void customTypeIdInNodeQuery() {
        assertGeneratedContentContains(
                "nodeQuery/customTypeId", Set.of(NODE_QUERY),
                "createId(\"C\", _a_customer.fields(",
                ".where(_iv_nodeIdStrategy.hasIds(\"C\", id, _a_customer.fields("
        );
    }

    @Test
    @DisplayName("ID with custom key columns in Node query")
    void customKeyColumnsInNodeQuery() {
        assertGeneratedContentContains(
                "nodeQuery/customKeyColumns", Set.of(NODE_QUERY),
                "nodeIdStrategy.createId(\"Customer\", _a_customer.CUSTOMER_ID, _a_customer.EMAIL), DSL.row(",
                ".where(_iv_nodeIdStrategy.hasIds(\"Customer\", id, _a_customer.CUSTOMER_ID, _a_customer.EMAIL))"
        );
    }

    @Test
    @DisplayName("Default case in entity query")
    void entity() {
        assertGeneratedContentContains(
                "entity/default", Set.of(FEDERATION_QUERY),
                ".objectRow(\"id\", _iv_nodeIdStrategy.createId(\"Customer\", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())))).",
                ".where(_iv_nodeIdStrategy.hasId(\"Customer\", (String) _iv_inputMap.get(\"id\"), _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))).fetch"
        );
    }

    @Test
    @DisplayName("With custom ID in entity query")
    void customIdInEntityQuery() {
        assertGeneratedContentContains(
                "entity/custom", Set.of(FEDERATION_QUERY),
                "objectRow(\"id\", _iv_nodeIdStrategy.createId(\"C\", _a_customer.CUSTOMER_ID)",
                ".where(_iv_nodeIdStrategy.hasId(\"C\", (String) _iv_inputMap.get(\"id\"), _a_customer.CUSTOMER_ID"
        );
    }
}
