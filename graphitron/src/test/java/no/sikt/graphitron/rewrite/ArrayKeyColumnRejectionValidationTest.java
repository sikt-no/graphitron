package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R446 validation decision: an array-typed column used as a <em>key element</em> must be rejected
 * at {@code validate} time rather than emitted. Ordinary (non-key) array columns flow through the
 * type-lift and generate fine (pinned by {@link ArrayColumnCodegenPipelineTest}); the key sites are
 * different because Java arrays have reference-identity {@code equals}/{@code hashCode}, so an array
 * value used as a NodeId key or a DataLoader batch key would compare by reference and mis-match
 * equal-content rows. Merely "not throwing" at those sites turns a build-time crash into a silent
 * runtime correctness bug, so they get an author-facing error instead.
 *
 * <p>This pins the {@code @node} key-column arm (the constructable case: a {@code keyColumns:}
 * entry can name any column, including an array column). The DataLoader batch-key arm shares the
 * same {@code ArrayTypeName} detection in {@code validateField} over {@code BatchKeyField}; it is
 * unconstructable from a real schema (a DataLoader keys off an FK/PK correlation and PostgreSQL
 * does not allow an array column as an FK target), so there is no fixture for it here.
 */
@PipelineTier
class ArrayKeyColumnRejectionValidationTest {

    @Test
    void nodeKeyColumn_thatIsArrayTyped_isRejectedAtValidateTime() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { arrayHolder: ArrayHolder }
            type ArrayHolder implements Node @table(name: "array_holder") @node(typeId: "AH", keyColumns: ["flags"]) {
                id: ID! @nodeId
                label: String @field(name: "label")
            }
            """);

        var errors = new GraphitronSchemaValidator().validate(schema);

        assertThat(errors)
            .as("an array-typed @node key column must be rejected with a clear author error")
            .anyMatch(e -> e.message().contains("ArrayHolder")
                && e.message().contains("flags")
                && e.message().toLowerCase().contains("array"));
    }

    @Test
    void nodeKeyColumn_thatIsScalar_isAccepted() {
        // Control: the same type keyed on the scalar PK column produces no array-key rejection.
        var schema = TestSchemaHelper.buildSchema("""
            type Query { arrayHolder: ArrayHolder }
            type ArrayHolder implements Node @table(name: "array_holder") @node(typeId: "AH", keyColumns: ["id"]) {
                id: ID! @nodeId
                label: String @field(name: "label")
            }
            """);

        var errors = new GraphitronSchemaValidator().validate(schema);

        assertThat(errors)
            .as("a scalar @node key column must not trip the array-key rejection")
            .noneMatch(e -> e.message().contains("flags")
                && e.message().toLowerCase().contains("array columns cannot be used as nodeid"));
    }
}
