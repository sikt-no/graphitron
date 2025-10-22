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
                        public static Customer queryForQuery(DSLContext ctx, NodeIdStrategy nodeIdStrategy,
                                SelectionSet select) {
                            var _a_customer = CUSTOMER.as("customer_2168032777");
                            return ctx
                                    .select(queryForQuery_customer(_a_customer, nodeIdStrategy))
                                    .from(_a_customer)
                                    .fetchOne(it -> it.into(Customer.class));
                        }
                        private static SelectField<Customer> queryForQuery_customer(
                                no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer _a_customer,
                                NodeIdStrategy nodeIdStrategy) {
                            return DSL.row(nodeIdStrategy.createId("Customer", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Customer::new));
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
                            private static SelectField<fake.graphql.example.model.Address> queryForQuery_customer_address(
                                    Address _a_address, NodeIdStrategy nodeIdStrategy) {
                                return DSL.row(nodeIdStrategy.createId("Address", _a_address.fields(_a_address.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(fake.graphql.example.model.Address::new));
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
                "DSL.row(nodeIdStrategy.createId(\"Customer\", _a_address_2"
        );
    }

    @Test
    @DisplayName("ID with custom type ID in splitQuery")
    void customTypeIdInSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/customTypeId",
                "DSL.row(nodeIdStrategy.createId(\"C\", _a_address_2"
        );
    }

    @Test
    @DisplayName("ID using custom key columns in splitQuery")
    void customKeyColumnsInSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/customKeyColumns",
                "DSL.row(nodeIdStrategy.createId(\"Address\", _a_customer_2168032777_address.ADDRESS_ID)).mapping"
        );
    }

    @Test
    @DisplayName("Default ID in Node query")
    void nodeQuery() {
        assertGeneratedContentContains(
                "nodeQuery/default", Set.of(NODE_QUERY),
                "nodeIdStrategy.createId(\"Customer\", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())), DSL.row(",
                ".where(nodeIdStrategy.hasIds(\"Customer\", ids, _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())))"
        );
    }

    @Test
    @DisplayName("ID with custom type ID in Node query")
    void customTypeIdInNodeQuery() {
        assertGeneratedContentContains(
                "nodeQuery/customTypeId", Set.of(NODE_QUERY),
                "createId(\"C\", _a_customer.fields(",
                ".where(nodeIdStrategy.hasIds(\"C\", ids, _a_customer.fields("
        );
    }

    @Test
    @DisplayName("ID with custom key columns in Node query")
    void customKeyColumnsInNodeQuery() {
        assertGeneratedContentContains(
                "nodeQuery/customKeyColumns", Set.of(NODE_QUERY),
                "nodeIdStrategy.createId(\"Customer\", _a_customer.CUSTOMER_ID, _a_customer.EMAIL), DSL.row(",
                ".where(nodeIdStrategy.hasIds(\"Customer\", ids, _a_customer.CUSTOMER_ID, _a_customer.EMAIL))"
        );
    }

    @Test
    @DisplayName("Default case in entity query")
    void entity() {
        assertGeneratedContentContains(
                "entity/default", Set.of(FEDERATION_QUERY),
                ".objectRow(\"id\", nodeIdStrategy.createId(\"Customer\", _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray())))).",
                ".where(nodeIdStrategy.hasId(\"Customer\", (String) _inputMap.get(\"id\"), _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))).fetch"
        );
    }

    @Test
    @DisplayName("With custom ID in entity query")
    void customIdInEntityQuery() {
        assertGeneratedContentContains(
                "entity/custom", Set.of(FEDERATION_QUERY),
                "objectRow(\"id\", nodeIdStrategy.createId(\"C\", _a_customer.CUSTOMER_ID)",
                ".where(nodeIdStrategy.hasId(\"C\", (String) _inputMap.get(\"id\"), _a_customer.CUSTOMER_ID"
        );
    }
}
