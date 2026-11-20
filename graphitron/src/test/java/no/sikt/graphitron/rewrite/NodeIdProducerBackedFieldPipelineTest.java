package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.diagnostics.RejectionKind;

/**
 * {@code @nodeId} on an output field whose value a producer method returns. A classifier that can
 * see such a field answers the producer directive first: the {@code @externalField} arm on a table
 * parent returns before its {@code @nodeId} arm is reached. The directive was therefore dropped
 * without a word and the producer's own value reached the consumer where an encoded node id had
 * been asked for, which is the one direction the accept-set guard cannot see: nothing was being
 * generated at the coordinate, so nothing went red.
 *
 * <p>Pins the deferral that closes it through the placement gate, and pins the three things the
 * gate must not touch: a producer-backed field carrying no {@code @nodeId}, a coordinate that
 * encodes today, and a coordinate whose own rejection is the more specific one.
 */
@PipelineTier
class NodeIdProducerBackedFieldPipelineTest {

    private static final String EXTERNAL_STUB = "no.sikt.graphitron.rewrite.TestExternalFieldStub";

    @Test
    void anExternalFieldProducedValueIsRefusedRatherThanLeftUnencoded() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node {
                id: ID! @nodeId
                producedNodeId: ID @nodeId(typeName: "Film")
                    @externalField(reference: {className: "%s", method: "rating"})
            }
            type Query { film: Film }
            """.formatted(EXTERNAL_STUB));

        var field = schema.field("Film", "producedNodeId");
        assertThat(field)
            .as("a @nodeId whose value @externalField produces must not classify as a plain computed field")
            .isInstanceOf(UnclassifiedField.class);
        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(RejectionKind.of(rejection))
            .as("the shape is one graphitron means to carry out, so the refusal is a deferral")
            .isEqualTo(RejectionKind.DEFERRED);
        assertThat(rejection.message())
            .contains("@nodeId classifies but does not encode")
            .contains("@externalField")
            .contains("jOOQ expression");
    }

    @Test
    void aProducerBackedFieldCarryingNoNodeIdIsUntouched() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node {
                id: ID! @nodeId
                computed: String @externalField(reference: {className: "%s", method: "rating"})
            }
            type Query { film: Film }
            """.formatted(EXTERNAL_STUB));

        assertThat(schema.field("Film", "computed"))
            .as("the gate reads @nodeId, not the producer directive; a producer alone still classifies")
            .isInstanceOf(ChildField.ComputedField.class);
    }

    @Test
    void aNodeIdTheParentsOwnColumnsBackStillEncodes() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node {
                id: ID! @nodeId
                computed: String @externalField(reference: {className: "%s", method: "rating"})
            }
            type Query { film: Film }
            """.formatted(EXTERNAL_STUB));

        var id = schema.field("Film", "id");
        assertThat(id)
            .as("the gate adds refusals and takes no encode away")
            .isInstanceOf(ChildField.ColumnBackedField.class);
        assertThat(((ChildField.ColumnBackedField) id).compaction())
            .isInstanceOf(CallSiteCompaction.NodeIdEncodeKeys.class);
    }

    @Test
    void aCoordinateThatAlreadyFailedKeepsItsOwnRejection() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node {
                id: ID! @nodeId
                producedNodeId: ID @nodeId(typeName: "Film")
                    @externalField(reference: {className: "no.sikt.does.not.Exist", method: "rating"})
            }
            type Query { film: Film }
            """);

        var field = schema.field("Film", "producedNodeId");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).rejection().message())
            .as("the unresolvable reference is the more specific cause and must survive the gate")
            .doesNotContain("@nodeId classifies but does not encode");
    }
}
