package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Mutation queries - Data returned from mutations")
public class MutationOutputTest extends MutationQueryTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "returningResult";
    }

    @Test
    @DisplayName("Returning node ID field")
    void nodeId() {
        assertGeneratedContentContains("nodeId",
                "String mutationForMutation",
                ".returningResult(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetchOne(_iv_it -> _iv_it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning scalar field")
    void scalar() {
        assertGeneratedContentContains("scalar",
                "Integer mutationForMutation",
                ".returningResult(CUSTOMER.CUSTOMER_ID)" +
                        ".fetchOne(_iv_it -> _iv_it.into(Integer.class))"
        );
    }

    @Test
    @DisplayName("Returning node ID field wrapped in type without table")
    void wrappedNodeId() {
        assertGeneratedContentContains("wrappedNodeId",
                "CustomerNodeInputTable in, SelectionSet _iv_select)",
                ".returningResult(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetchOne(_iv_it -> _iv_it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning scalar field wrapped in type without table")
    void wrappedScalar() {
        assertGeneratedContentContains("wrappedScalar",
                "CustomerNodeInputTable in, SelectionSet _iv_select)",
                ".returningResult(CUSTOMER.CUSTOMER_ID)" +
                        ".fetchOne(_iv_it -> _iv_it.into(Integer.class))"
        );
    }

    @Test
    @DisplayName("Returning a list of node IDs")
    void nodeIdList() {
        assertGeneratedContentContains("nodeIdList",
                "List<String> mutationForMutation",
                ".returningResult(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetch(_iv_it -> _iv_it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning listed scalar field")
    void scalarList() {
        assertGeneratedContentContains("scalarList",
                "List<Integer> mutationForMutation",
                ".returningResult(CUSTOMER.CUSTOMER_ID)" +
                        ".fetch(_iv_it -> _iv_it.into(Integer.class))"
        );
    }

    @Test
    @DisplayName("Returning listed node ID field wrapped in type without table")
    void wrappedNodeIdList() {
        assertGeneratedContentContains("wrappedNodeIdList",
                "List<String> mutationForMutation",
                ".returningResult(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetch(_iv_it -> _iv_it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning listed scalar field wrapped in type without table")
    void wrappedScalarList() {
        assertGeneratedContentContains("wrappedScalarList",
                "List<Integer> mutationForMutation",
                ".returningResult(CUSTOMER.CUSTOMER_ID)" +
                        ".fetch(_iv_it -> _iv_it.into(Integer.class))"
        );
    }

    @Test
    @DisplayName("Returning table object")
    void tableObject() {
        assertGeneratedContentContains("tableObject",
                "CustomerNode mutationForMutation",
                ".returningResult(DSL.row(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".mapping(Functions.nullOnAllNull(CustomerNode::new)))" +
                        ".fetchOne(_iv_it -> _iv_it.into(CustomerNode.class))"
        );
    }

    @Test
    @DisplayName("Returning table object wrapped in type without table")
    void wrappedTableObject() {
        assertGeneratedContentContains("wrappedTableObject",
                "CustomerNode mutationForMutation",
                ".returningResult(DSL.row(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".mapping(Functions.nullOnAllNull(CustomerNode::new)))" +
                        ".fetchOne(_iv_it -> _iv_it.into(CustomerNode.class))"
        );
    }

    @Test
    @DisplayName("Returning listed table object")
    void tableObjectList() {
        assertGeneratedContentContains("tableObjectList",
                "List<CustomerNode> mutationForMutation",
                ".returningResult(DSL.row(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".mapping(Functions.nullOnAllNull(CustomerNode::new)))" +
                        ".fetch(_iv_it -> _iv_it.into(CustomerNode.class))"
        );
    }

    @Test
    @DisplayName("Returning list of table objects wrapped in type without table")
    void wrappedTableObjectList() {
        assertGeneratedContentContains("wrappedTableObjectList",
                "List<CustomerNode> mutationForMutation",
                ".returningResult(DSL.row(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".mapping(Functions.nullOnAllNull(CustomerNode::new)))" +
                        ".fetch(_iv_it -> _iv_it.into(CustomerNode.class))"
        );
    }
}
