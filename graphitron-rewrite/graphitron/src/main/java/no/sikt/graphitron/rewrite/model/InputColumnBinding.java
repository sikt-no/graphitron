package no.sikt.graphitron.rewrite.model;

/**
 * Maps one slot of a composite-key input source to a target jOOQ column.
 *
 * <p>Sealed split on the source-shape axis: callers either reach the slot through a Map key
 * (a GraphQL input field name, the canonical {@code @lookupKey}-on-input-field shape) or through
 * a positional index into a decoded jOOQ {@code Record<N>} (the composite-PK NodeId shape, where
 * the wire-to-typed translation runs once at the arg layer via
 * {@link CallSiteExtraction.NodeIdDecodeKeys}).
 *
 * <p>Used today by {@code ArgumentRef.TableInputArg.fieldBindings} (narrowed to
 * {@code List<MapBinding>} since every existing call site produces Map-keyed bindings) and,
 * post-R50 phase (f), by the {@code MapInput} / {@code DecodedRecord} arms of
 * {@code LookupMapping.ColumnMapping.LookupArg}.
 *
 * <p>{@link RecordBinding} carries no {@code extraction} slot per <em>Narrow component types
 * over broad interfaces</em>: for the composite-PK NodeId case the wire-to-typed translation
 * lives once on the enclosing {@code DecodedRecord.decodeMethod}, so per-binding extraction is
 * structurally always {@code Direct} and the slot would be dead.
 */
public sealed interface InputColumnBinding permits InputColumnBinding.MapBinding, InputColumnBinding.RecordBinding {

    /** The column on the target table this binding equates against. */
    ColumnRef targetColumn();

    /**
     * Map-keyed binding: the slot's value is reached by {@code Map.get(fieldName)} on a
     * GraphQL input object's argument value.
     *
     * <p>{@code fieldName} is the GraphQL field name (e.g. {@code "filmId"}).
     * {@code extraction} tells the generator how to read the value at the call site
     * ({@code Direct}, {@code JooqConvert}, {@code EnumValueOf}, {@code TextMapLookup}, …).
     */
    record MapBinding(
        String fieldName,
        ColumnRef targetColumn,
        CallSiteExtraction extraction
    ) implements InputColumnBinding {}

    /**
     * Slot-indexed binding: the slot's value is reached by index into a decoded jOOQ
     * {@code Record<N>} produced by a per-NodeType {@code decode<TypeName>} helper.
     *
     * <p>{@code index} is the 0-based slot position within the Record. No extraction slot;
     * the wire-to-typed translation lives once on the enclosing {@code DecodedRecord.decodeMethod}.
     */
    record RecordBinding(
        int index,
        ColumnRef targetColumn
    ) implements InputColumnBinding {}
}
