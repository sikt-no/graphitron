package no.sikt.graphitron.lsp.inlay;

import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;

/**
 * R217 — LSP-facing labels for {@link FieldClassification} / {@link TypeClassification}
 * projection records. The label <em>is</em> the projection-record simple name: a hint or
 * hover renders {@code "Column"}, {@code "Table"}, {@code "DmlMutation"}, etc., and the
 * developer reading it can grep that name in the codebase to discover the taxonomy the
 * generator dispatches on.
 *
 * <p>This couples the LSP-visible label vocabulary to the projection-record names; a
 * future rename of (say) {@code TableTarget} to {@code JoinedColumnTarget} is also a
 * user-visible-string change. R217 accepts that coupling deliberately as the pedagogical
 * mechanism that lets the LSP teach the model. See class-level Javadoc on
 * {@link FieldClassification} / {@link TypeClassification} for the dual-role note.
 *
 * <p>The switches keep their exhaustive structure even though every arm body is uniform:
 * a new permit added to either sealed projection set fails this switch to compile until
 * the author confirms the default {@code getSimpleName()} shape is acceptable.
 */
public final class LspClassificationLabels {

    private LspClassificationLabels() {}

    /**
     * Label for a {@link FieldClassification} projection: the projection-record simple
     * name. Exhaustive over the sealed permit set; the uniform-body switch survives as
     * a compile-time tripwire for "a new projection variant exists; come confirm the
     * default label reads sensibly".
     */
    public static String projectionLabel(FieldClassification c) {
        return switch (c) {
            case FieldClassification.Column x -> FieldClassification.Column.class.getSimpleName();
            case FieldClassification.ColumnReference x -> FieldClassification.ColumnReference.class.getSimpleName();
            case FieldClassification.ParticipantCrossTable x -> FieldClassification.ParticipantCrossTable.class.getSimpleName();
            case FieldClassification.CompositeColumn x -> FieldClassification.CompositeColumn.class.getSimpleName();
            case FieldClassification.CompositeColumnReference x -> FieldClassification.CompositeColumnReference.class.getSimpleName();
            case FieldClassification.TableTarget x -> FieldClassification.TableTarget.class.getSimpleName();
            case FieldClassification.RecordTableTarget x -> FieldClassification.RecordTableTarget.class.getSimpleName();
            case FieldClassification.TableMethod x -> FieldClassification.TableMethod.class.getSimpleName();
            case FieldClassification.TableInterface x -> FieldClassification.TableInterface.class.getSimpleName();
            case FieldClassification.Polymorphic x -> FieldClassification.Polymorphic.class.getSimpleName();
            case FieldClassification.Nesting x -> FieldClassification.Nesting.class.getSimpleName();
            case FieldClassification.ServiceBacked x -> FieldClassification.ServiceBacked.class.getSimpleName();
            case FieldClassification.RecordOrProperty x -> FieldClassification.RecordOrProperty.class.getSimpleName();
            case FieldClassification.Computed x -> FieldClassification.Computed.class.getSimpleName();
            case FieldClassification.InputUnbound x -> FieldClassification.InputUnbound.class.getSimpleName();
            case FieldClassification.Errors x -> FieldClassification.Errors.class.getSimpleName();
            case FieldClassification.SingleRecordId x -> FieldClassification.SingleRecordId.class.getSimpleName();
            case FieldClassification.SingleRecordIdFromReturning x -> FieldClassification.SingleRecordIdFromReturning.class.getSimpleName();
            case FieldClassification.QueryTable x -> FieldClassification.QueryTable.class.getSimpleName();
            case FieldClassification.QueryTableMethod x -> FieldClassification.QueryTableMethod.class.getSimpleName();
            case FieldClassification.QueryNode x -> FieldClassification.QueryNode.class.getSimpleName();
            case FieldClassification.QueryTableInterface x -> FieldClassification.QueryTableInterface.class.getSimpleName();
            case FieldClassification.QueryPolymorphic x -> FieldClassification.QueryPolymorphic.class.getSimpleName();
            case FieldClassification.QueryService x -> FieldClassification.QueryService.class.getSimpleName();
            case FieldClassification.DmlMutation x -> FieldClassification.DmlMutation.class.getSimpleName();
            case FieldClassification.MutationService x -> FieldClassification.MutationService.class.getSimpleName();
            case FieldClassification.DmlRecord x -> FieldClassification.DmlRecord.class.getSimpleName();
            case FieldClassification.Unclassified x -> FieldClassification.Unclassified.class.getSimpleName();
        };
    }

    /**
     * Label for a {@link TypeClassification} projection: the projection-record simple
     * name. Exhaustive over the sealed permit set; same tripwire role as
     * {@link #projectionLabel(FieldClassification)}.
     */
    public static String projectionTypeLabel(TypeClassification c) {
        return switch (c) {
            case TypeClassification.Table x -> TypeClassification.Table.class.getSimpleName();
            case TypeClassification.Node x -> TypeClassification.Node.class.getSimpleName();
            case TypeClassification.TableInterface x -> TypeClassification.TableInterface.class.getSimpleName();
            case TypeClassification.Interface x -> TypeClassification.Interface.class.getSimpleName();
            case TypeClassification.Union x -> TypeClassification.Union.class.getSimpleName();
            case TypeClassification.JavaRecord x -> TypeClassification.JavaRecord.class.getSimpleName();
            case TypeClassification.JavaRecordInput x -> TypeClassification.JavaRecordInput.class.getSimpleName();
            case TypeClassification.JooqRecord x -> TypeClassification.JooqRecord.class.getSimpleName();
            case TypeClassification.JooqRecordInput x -> TypeClassification.JooqRecordInput.class.getSimpleName();
            case TypeClassification.JooqTableRecord x -> TypeClassification.JooqTableRecord.class.getSimpleName();
            case TypeClassification.JooqTableRecordInput x -> TypeClassification.JooqTableRecordInput.class.getSimpleName();
            case TypeClassification.PojoResult x -> TypeClassification.PojoResult.class.getSimpleName();
            case TypeClassification.PojoInput x -> TypeClassification.PojoInput.class.getSimpleName();
            case TypeClassification.TableInput x -> TypeClassification.TableInput.class.getSimpleName();
            case TypeClassification.Root x -> TypeClassification.Root.class.getSimpleName();
            case TypeClassification.Connection x -> TypeClassification.Connection.class.getSimpleName();
            case TypeClassification.Edge x -> TypeClassification.Edge.class.getSimpleName();
            case TypeClassification.PageInfo x -> TypeClassification.PageInfo.class.getSimpleName();
            case TypeClassification.Error x -> TypeClassification.Error.class.getSimpleName();
            case TypeClassification.Enum x -> TypeClassification.Enum.class.getSimpleName();
            case TypeClassification.Scalar x -> TypeClassification.Scalar.class.getSimpleName();
            case TypeClassification.PlainObject x -> TypeClassification.PlainObject.class.getSimpleName();
            case TypeClassification.Unclassified x -> TypeClassification.Unclassified.class.getSimpleName();
        };
    }
}
