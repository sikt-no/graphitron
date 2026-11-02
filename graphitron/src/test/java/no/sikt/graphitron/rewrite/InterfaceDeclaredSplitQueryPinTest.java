package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what the build does with {@code @splitQuery} on a field declared on a multitable interface
 * itself: the interface hosts no fetcher class, so no field is classified for it and no loader name
 * is ever built for it, and the build reports nothing. Only the participants' own declarations
 * classify as batched fields, which is why a DataLoader name needs the declaring object type and
 * never an interface.
 */
@PipelineTier
class InterfaceDeclaredSplitQueryPinTest {

    @Test
    void splitQueryOnTheInterfaceItself_classifiesNoField_onlyTheParticipantsDeclarationsBatch() {
        var schema = TestSchemaHelper.buildSchema("""
            interface Item @table(name: "fan_base") @discriminate(on: "fan_kind") {
                fanBaseId: Int! @field(name: "fan_base_id")
                target: Target @splitQuery @reference(path: [{key: "fan_base_alpha_target_id_fkey"}])
            }
            type Alpha implements Item @table(name: "fan_base") @discriminator(value: "ALPHA") {
                fanBaseId: Int! @field(name: "fan_base_id")
                target: Target @splitQuery @reference(path: [{key: "fan_base_alpha_target_id_fkey"}])
            }
            type Beta implements Item @table(name: "fan_base") @discriminator(value: "BETA") {
                fanBaseId: Int! @field(name: "fan_base_id")
                target: Target @splitQuery @reference(path: [{key: "fan_base_beta_target_id_fkey"}])
            }
            type Target @table(name: "fan_target") { fanTargetId: Int! @field(name: "fan_target_id") }
            type Query { items: [Item!]! }
            """);

        assertThat(schema.diagnostics())
            .as("the build accepts the directive on an interface-declared field without a diagnostic")
            .isEmpty();
        assertThat(schema.fieldsOf("Item"))
            .as("the interface hosts no fetcher class, so no field is classified for it")
            .isEmpty();
        assertThat(schema.field("Alpha", "target")).isInstanceOf(ChildField.BatchedTableField.class);
        assertThat(schema.field("Beta", "target")).isInstanceOf(ChildField.BatchedTableField.class);
    }
}
