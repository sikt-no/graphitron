package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.db.EntityDBClassGenerator;
import no.sikt.graphitron.generators.db.NodeInterfaceDBClassGenerator;
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
        return List.of(new InterfaceOnlyFetchDBClassGenerator(schema), new NodeInterfaceDBClassGenerator(schema), new MapOnlyFetchDBClassGenerator(schema), new EntityDBClassGenerator(schema));
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
                "row(nodeIdStrategy.createId(\"Customer\", _customer.fields(_customer.getPrimaryKey().getFieldsArray()))).mapping"
        );
    }

    @Test
    @DisplayName("ID in nested query")
    void nested() {
        assertGeneratedContentContains(
                "root/nested", Set.of(CUSTOMER_QUERY),
                "createId(\"Address\", customer_2952383337_address.fields(customer_2952383337_address.getPrimaryKey().getFieldsArray())))" +
                        ".mapping(Functions.nullOnAllNull(Address"
        );
    }

    @Test
    @DisplayName("With ID using custom type ID")
    void customTypeId() {
        assertGeneratedContentContains(
                "root/customTypeId", Set.of(CUSTOMER_QUERY),
                ".createId(\"C\", _customer.fields("
        );
    }

    @Test
    @DisplayName("With ID using custom key columns")
    void customKeyColumns() {
        assertGeneratedContentContains(
                "root/customKeyColumns", Set.of(CUSTOMER_QUERY),
                ".createId(\"Customer\", _customer.CUSTOMER_ID, _customer.EMAIL)).mapping"
        );
    }

    @Test
    @DisplayName("With custom key columns with wrong case")
    void customKeyColumnsWrongCase() {
        assertGeneratedContentContains(
                "root/customKeyColumnsWrongCase", Set.of(CUSTOMER_QUERY),
                ".createId(\"Customer\", _customer.CUSTOMER_ID, _customer.EMAIL)).mapping"
        );
    }

    @Test
    @DisplayName("Default ID in splitQuery")
    void splitQuery() {
        assertGeneratedContentContains(
                "splitQuery/default",
                "DSL.select(DSL.row(nodeIdStrategy.createId(\"Customer\", address_2030472956"
        );
    }

    @Test
    @DisplayName("ID with custom type ID in splitQuery")
    void customTypeIdInSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/customTypeId",
                "DSL.select(DSL.row(nodeIdStrategy.createId(\"C\", address_2"
        );
    }

    @Test
    @DisplayName("ID using custom key columns in splitQuery")
    void customKeyColumnsInSplitQuery() {
        assertGeneratedContentContains(
                "splitQuery/customKeyColumns",
                "DSL.select(DSL.row(nodeIdStrategy.createId(\"Address\", customer_2952383337_address.ADDRESS_ID)).mapping"
        );
    }

    @Test
    @DisplayName("Default ID in Node query")
    void nodeQuery() {
        assertGeneratedContentContains(
                "nodeQuery/default", Set.of(NODE_QUERY),
                "nodeIdStrategy.createId(\"Customer\", _customer.fields(_customer.getPrimaryKey().getFieldsArray())), DSL.row(",
                ".where(nodeIdStrategy.hasIds(\"Customer\", ids, _customer.fields(_customer.getPrimaryKey().getFieldsArray())))"
        );
    }

    @Test
    @DisplayName("ID with custom type ID in Node query")
    void customTypeIdInNodeQuery() {
        assertGeneratedContentContains(
                "nodeQuery/customTypeId", Set.of(NODE_QUERY),
                "createId(\"C\", _customer.fields(",
                ".where(nodeIdStrategy.hasIds(\"C\", ids, _customer.fields("
        );
    }

    @Test
    @DisplayName("ID with custom key columns in Node query")
    void customKeyColumnsInNodeQuery() {
        assertGeneratedContentContains(
                "nodeQuery/customKeyColumns", Set.of(NODE_QUERY),
                "nodeIdStrategy.createId(\"Customer\", _customer.CUSTOMER_ID, _customer.EMAIL), DSL.row(",
                ".where(nodeIdStrategy.hasIds(\"Customer\", ids, _customer.CUSTOMER_ID, _customer.EMAIL))"
        );
    }

    @Test
    @DisplayName("Default case in entity query")
    void entity() {
        assertGeneratedContentContains(
                "entity/default", Set.of(FEDERATION_QUERY),
                ".objectRow(\"id\", nodeIdStrategy.createId(\"Customer\", _customer.fields(_customer.getPrimaryKey().getFieldsArray())))).",
                ".where(nodeIdStrategy.hasId(\"Customer\", (String) _inputMap.get(\"id\"), _customer.fields(_customer.getPrimaryKey().getFieldsArray()))).fetch"
        );
    }

    @Test
    @DisplayName("With custom ID in entity query")
    void customIdInEntityQuery() {
        assertGeneratedContentContains(
                "entity/custom", Set.of(FEDERATION_QUERY),
                "objectRow(\"id\", nodeIdStrategy.createId(\"C\", _customer.CUSTOMER_ID)",
                ".where(nodeIdStrategy.hasId(\"C\", (String) _inputMap.get(\"id\"), _customer.CUSTOMER_ID"
        );
    }
}
