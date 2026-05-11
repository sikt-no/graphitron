package no.sikt.graphitron.rewrite.model;

/**
 * The single-record carrier's data-field element descriptor. Two arms:
 *
 * <ul>
 *   <li>{@link Table} — the element type is a {@link GraphitronType.TableBackedType}; the
 *       data field's response rows come from the upstream DML's PK-only {@code Result} via a
 *       follow-up SELECT (Phase 1 DML carrier, Phase 2 {@code @service} {@code @table}-element).</li>
 *   <li>{@link Record} — the element type is a record-backed {@link GraphitronType.ResultType}
 *       (a {@link GraphitronType.PojoResultType.Backed}, {@link GraphitronType.JavaRecordType},
 *       {@link GraphitronType.JooqRecordType}, or {@link GraphitronType.JooqTableRecordType});
 *       the data field's value is the {@code @service}-produced domain record directly (no
 *       SELECT). Phase 2 only — DML carriers reject {@code Record} element kinds at the
 *       mutation classifier.</li>
 * </ul>
 *
 * The split is the "carry the resolution in the type system" principle: instead of forking on
 * "does {@link SingleRecordCarrierShape#dataElement} hint at a table or a record" at every
 * consumer, every site exhaustively switches over this sealed type and pulls the relevant
 * components from the right arm.
 *
 * <p>{@code wrapper} is the data field's SDL outer wrapper (single or list, optional or
 * mandatory); same value for both arms.
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
}
