package no.sikt.graphitron.queries.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Mutation queries - Data returned from mutations")
public class MutationOutputTest extends MutationQueryTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "returningResult";
    }

    @Test
    @DisplayName("Returning scalar field")
    void scalar() {
        assertGeneratedContentContains("scalar",
                "String mutationForMutation",
                ".returningResult(nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetchOne(it -> it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning scalar field wrapped in type without table")
    void wrappedScalar() {
        assertGeneratedContentContains("wrappedScalar",
                "CustomerNodeInputTable in, SelectionSet select)",
                ".returningResult(nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetchOne(it -> it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning listed scalar field")
    void scalarList() {
        assertGeneratedContentContains("scalarList",
                "List<String> mutationForMutation",
                ".returningResult(nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetch(it -> it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning listed scalar field wrapped in type without table")
    void wrappedScalarList() {
        assertGeneratedContentContains("wrappedScalarList",
                "List<String> mutationForMutation",
                ".returningResult(nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".fetch(it -> it.into(String.class))"
        );
    }

    @Test
    @DisplayName("Returning table object")
    void tableObject() {
        assertGeneratedContentContains("tableObject",
                "CustomerNode mutationForMutation",
                ".returningResult(DSL.row(nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".mapping(Functions.nullOnAllNull(CustomerNode::new)))" +
                        ".fetchOne(it -> it.into(CustomerNode.class))"
        );
    }

    @Test
    @DisplayName("Returning table object wrapped in type without table")
    void wrappedTableObject() {
        assertGeneratedContentContains("wrappedTableObject",
                "CustomerNode mutationForMutation",
                ".returningResult(DSL.row(nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".mapping(Functions.nullOnAllNull(CustomerNode::new)))" +
                        ".fetchOne(it -> it.into(CustomerNode.class))"
        );
    }

    @Test
    @DisplayName("Returning listed table object")
    void tableObjectList() {
        assertGeneratedContentContains("tableObjectList",
                "List<CustomerNode> mutationForMutation",
                ".returningResult(DSL.row(nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".mapping(Functions.nullOnAllNull(CustomerNode::new)))" +
                        ".fetch(it -> it.into(CustomerNode.class))"
        );
    }

    @Test
    @DisplayName("Returning list of table objects wrapped in type without table")
    void wrappedTableObjectList() {
        assertGeneratedContentContains("wrappedTableObjectList",
                "List<CustomerNode> mutationForMutation",
                ".returningResult(DSL.row(nodeIdStrategy.createId(\"CustomerNode\", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))" +
                        ".mapping(Functions.nullOnAllNull(CustomerNode::new)))" +
                        ".fetch(it -> it.into(CustomerNode.class))"
        );
    }
}
