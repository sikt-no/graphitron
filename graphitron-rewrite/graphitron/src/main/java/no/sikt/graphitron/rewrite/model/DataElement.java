package no.sikt.graphitron.rewrite.model;

/**
 * The single-record carrier's data-field element descriptor. Three arms:
 *
 * <ul>
 *   <li>{@link Table} — the element type is a {@link GraphitronType.TableBackedType}; the
 *       data field's response rows come from the upstream DML's PK-only {@code Result} via a
 *       follow-up SELECT (Phase 1 DML carrier, Phase 2 {@code @service} {@code @table}-element).
 *       For payload-returning DELETE (R156) the same arm carries a {@code @table}-element data
 *       field but the response fetcher reads directly off the PK-only RETURNING record (no
 *       follow-up SELECT — the row is gone); the per-field projection plan lives on the new
 *       {@link ChildField#SingleRecordTableFieldFromReturning} sibling.</li>
 *   <li>{@link Record} — the element type is a record-backed {@link GraphitronType.ResultType}
 *       (a {@link GraphitronType.PojoResultType.Backed}, {@link GraphitronType.JavaRecordType},
 *       {@link GraphitronType.JooqRecordType}, or {@link GraphitronType.JooqTableRecordType});
 *       the data field's value is the {@code @service}-produced domain record directly (no
 *       SELECT). Phase 2 only — DML carriers reject {@code Record} element kinds at the
 *       mutation classifier.</li>
 *   <li>{@link Id} — the element type is the GraphQL {@code ID} scalar (R156). Used by
 *       payload-returning DELETE carriers where the response data is the encoded primary key
 *       of each deleted row. Admitted only on {@code @mutation(typeName: DELETE)}; other verbs
 *       reject at carrier-walk classify time.</li>
 * </ul>
 *
 * The split is the "carry the resolution in the type system" principle: instead of forking on
 * "does {@link SingleRecordCarrierShape#dataElement} hint at a table or a record" at every
 * consumer, every site exhaustively switches over this sealed type and pulls the relevant
 * components from the right arm.
 *
 * <p>{@code wrapper} is the data field's SDL outer wrapper (single or list, optional or
 * mandatory); same value for all three arms.
 */
public sealed interface DataElement {

    /** The element type's SDL name. */
    String name();

    /** The data field's SDL outer wrapper. */
    FieldWrapper wrapper();

    /**
     * Element type is a {@link GraphitronType.TableBackedType}. {@code table} is the
     * resolved jOOQ {@link TableRef}; the data field's fetcher reads upstream rows by PK and
     * runs the response SELECT against this table.
     */
    record Table(String name, TableRef table, FieldWrapper wrapper) implements DataElement {}

    /**
     * Element type is a record-backed {@link GraphitronType.ResultType} with a non-null
     * backing class. {@code fqClassName} is the binary class name; the {@code @service}
     * classifier matches it against the method's reflected return type.
     */
    record Record(String name, String fqClassName, FieldWrapper wrapper) implements DataElement {}

    /**
     * Element type is the GraphQL {@code ID} scalar. Used by payload-returning DELETE carriers
     * (R156) where the response data is the encoded primary key of each deleted row.
     *
     * <p>The arm describes the element SDL shape only. The projection plan — how to turn the
     * source {@code Record}'s PK columns into an encoded {@code ID} value at runtime — lives in
     * a sibling {@link CallSiteCompaction.NodeIdEncodeKeys} slot on the per-field carrier (the
     * new {@link ChildField#SingleRecordIdFieldFromReturning} sibling), the same slot every
     * other NodeId-encoded field uses. Mixing the encoder reference into this record would
     * conflate two axes — "what's the element shape" and "how to project it" — and create a
     * parallel home for an encoder reference that {@link CallSiteCompaction} already owns.
     *
     * <p>The compact constructor pins the admitted wrapper set to the two SDL shapes the
     * carrier walk admits ({@code ID} / {@code ID!} singleton, or {@code [ID!]} / {@code [ID!]!}
     * list-of-non-null). The classifier produces the SDL-level rejection on bad authoring; the
     * compact constructor produces the same rejection on a programming-error construction (e.g.
     * a future caller passing a list-of-nullable wrapper or a connection wrapper).
     */
    record Id(String name, FieldWrapper wrapper) implements DataElement {
        public Id {
            if (wrapper instanceof FieldWrapper.List list && list.itemNullable()) {
                throw new IllegalArgumentException(
                    "DataElement.Id wrapper must be singleton ID/ID! or list-of-non-null "
                    + "[ID!]/[ID!]!; got list-of-nullable wrapper " + wrapper);
            }
            if (!(wrapper instanceof FieldWrapper.Single || wrapper instanceof FieldWrapper.List)) {
                throw new IllegalArgumentException(
                    "DataElement.Id wrapper must be singleton ID/ID! or list-of-non-null "
                    + "[ID!]/[ID!]!; got " + wrapper);
            }
        }
    }
}
