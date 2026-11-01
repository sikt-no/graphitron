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
    void agreeingOnBoth_refusedAtAStep_whereTheStoreKeepsItsTwoParameterDeclaration() {
        // agreeingOnBoth is declared (Customer, Address) and (Customer, Address, String). The step
        // calls it agreeingOnBoth(customerAlias, addressAlias), which javac resolves to the
        // two-parameter declaration, and the store keeps that one with no defect. The walk folds every
        // declaration of the name into one shape and refuses the set on its parameter count, so the
        // field does not classify. agreeingOnArrival, whose two declarations have one count, is the
        // control: the parameter count is what refuses.
        var refused = TestSchemaHelper.buildSchema(customerToAddress("agreeingOnBoth"));
        assertThat(refused.field("Customer", "address")).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) refused.field("Customer", "address")).rejection().message())
            .contains("agreeingOnBoth").contains("could not be resolved");
        var control = TestSchemaHelper.buildSchema(customerToAddress("agreeingOnArrival"));
        assertThat(control.field("Customer", "address")).isNotInstanceOf(UnclassifiedField.class);
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
