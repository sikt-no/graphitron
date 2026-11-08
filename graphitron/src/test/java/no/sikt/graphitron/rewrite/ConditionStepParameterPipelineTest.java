package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL to classified schema for the parameters a path step's condition method takes: which declared
 * types the walk accepts at a position the generated call passes a table alias to. Asserted on the
 * classified field, not on generated code.
 */
@PipelineTier
class ConditionStepParameterPipelineTest {

    private static final String ROUTE_FIXTURES =
        "no.sikt.graphitron.rewrite.test.conditions.ConditionMethodRouteFixtures";

    @Test
    void tableLikeArrival_takesTheStepsTableAsAWildcard() {
        // tableLikeArrival(Customer, TableLike<?>): the generated call passes the address alias at
        // parameter 1, and a TableLike<?> takes any table as Table<?> does.
        var schema = TestSchemaHelper.buildSchema("""
            type Address @table(name: "address") { addressId: Int @field(name: "address_id") }
            type Customer @table(name: "customer") {
              address: Address @reference(path: [{table: "address", condition: {className: "%s", method: "tableLikeArrival"}}])
            }
            type Query { customers: [Customer!]! }
            """.formatted(ROUTE_FIXTURES));
        assertThat(schema.field("Customer", "address"))
            .as("a TableLike<?> condition parameter takes the step's table")
            .isNotInstanceOf(UnclassifiedField.class);
    }

    @Test
    void agreeingOnBoth_takesTheTwoParameterDeclarationTheStepsCallReaches() {
        // agreeingOnBoth is declared (Customer, Address) and (Customer, Address, String). The step
        // calls it agreeingOnBoth(customerAlias, addressAlias), which javac resolves to the
        // two-parameter declaration, and the store keeps that one with no defect; the declaration
        // with a String beside is no candidate, so the field classifies as the store accepts it.
        var schema = TestSchemaHelper.buildSchema(customerToAddress("agreeingOnBoth"));
        assertThat(schema.field("Customer", "address")).isNotInstanceOf(UnclassifiedField.class);
    }

    private static String customerToAddress(String method) {
        return """
            type Address @table(name: "address") { addressId: Int @field(name: "address_id") }
            type Customer @table(name: "customer") {
              address: Address @reference(path: [{condition: {className: "%s", method: "%s"}}])
            }
            type Query { customers: [Customer!]! }
            """.formatted(ROUTE_FIXTURES, method);
    }
}
