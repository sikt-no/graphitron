package no.sikt.graphitron.queries.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Mutation queries - Inferring table target, and returning data from mutations")
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
                "deleteFrom(CUSTOMER)",
                ".returningResult(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetchOne(_iv_it -> _iv_it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning scalar field")
    void scalar() {
        assertGeneratedContentContains("scalar",
                "Integer mutationForMutation",
                "deleteFrom(CUSTOMER)",
                ".returningResult(CUSTOMER.CUSTOMER_ID)" +
                        ".fetchOne(_iv_it -> _iv_it.into(Integer.class))"
        );
    }

    @Test
    @DisplayName("Returning node ID field wrapped in type without table")
    void wrappedNodeId() {
        assertGeneratedContentContains("wrappedNodeId",
                "CustomerRecord _mi_inRecord, SelectionSet _iv_select)",
                "deleteFrom(CUSTOMER)",
                ".returningResult(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetchOne(_iv_it -> _iv_it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning scalar field wrapped in type without table")
    void wrappedScalar() {
        assertGeneratedContentContains("wrappedScalar",
                "CustomerRecord _mi_inRecord, SelectionSet _iv_select)",
                "deleteFrom(CUSTOMER)",
                ".returningResult(CUSTOMER.CUSTOMER_ID)" +
                        ".fetchOne(_iv_it -> _iv_it.into(Integer.class))"
        );
    }

    @Test
    @DisplayName("Returning a list of node IDs")
    void nodeIdList() {
        assertGeneratedContentContains("nodeIdList",
                "List<String> mutationForMutation",
                "deleteFrom(CUSTOMER)",
                ".returningResult(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetch(_iv_it -> _iv_it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning listed scalar field")
    void scalarList() {
        assertGeneratedContentContains("scalarList",
                "List<Integer> mutationForMutation",
                "deleteFrom(CUSTOMER)",
                ".returningResult(CUSTOMER.CUSTOMER_ID)" +
                        ".fetch(_iv_it -> _iv_it.into(Integer.class))"
        );
    }

    @Test
    @DisplayName("Returning listed node ID field wrapped in type without table")
    void wrappedNodeIdList() {
        assertGeneratedContentContains("wrappedNodeIdList",
                "List<String> mutationForMutation",
                "deleteFrom(CUSTOMER)",
                ".returningResult(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetch(_iv_it -> _iv_it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning listed scalar field wrapped in type without table")
    void wrappedScalarList() {
        assertGeneratedContentContains("wrappedScalarList",
                "List<Integer> mutationForMutation",
                "deleteFrom(CUSTOMER)",
                ".returningResult(CUSTOMER.CUSTOMER_ID)" +
                        ".fetch(_iv_it -> _iv_it.into(Integer.class))"
        );
    }

    @Test
    @DisplayName("Returning table object")
    void tableObject() {
        assertGeneratedContentContains("tableObject",
                "CustomerNode mutationForMutation",
                "deleteFrom(CUSTOMER)",
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
                "deleteFrom(CUSTOMER)",
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
                "deleteFrom(CUSTOMER)",
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
                "deleteFrom(CUSTOMER)",
                ".returningResult(DSL.row(_iv_nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".mapping(Functions.nullOnAllNull(CustomerNode::new)))" +
                        ".fetch(_iv_it -> _iv_it.into(CustomerNode.class))"
        );
    }

    @Test
    @DisplayName("Returning table object with subquery reference")
    void tableObjectWithSubqueryReference() {
        assertGeneratedContentContains("tableObjectWithSubqueryReference",
                "{ var _a_customer_address = CUSTOMER.address()",
                "field(DSL.select(DSL.row(_a_customer_address.ADDRESS_ID).mapping(Functions.nullOnAllNull(Address::new))).from(_a_customer_address))"
        );
    }

    @Test
    @DisplayName("Returning table object with splitQuery reference")
    void tableObjectWithSplitQueryReference() {
        assertGeneratedContentContains("tableObjectWithSplitQueryReference",
                "{ return",
                "DSL.row(DSL.row(CUSTOMER.CUSTOMER_ID), _iv_nodeIdStrategy.createId",
                "getFieldsArray()))).mapping(Functions.nullOnAllNull(Customer"
        );
    }
}
