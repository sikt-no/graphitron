package no.sikt.graphitron.rewrite.model;

/**
 * How to write a typed Java value back into a wire-format value at a SELECT-side projection.
 *
 * <p>Symmetric counterpart to {@link CallSiteExtraction}: extraction reads a wire value into a
 * typed Java value at the call site; compaction writes a typed Java value back into a wire value
 * at the projection site. Both classify exhaustively at the parse boundary.
 *
 * <p>Carried by every output carrier that produces the field's value itself, which is two
 * families rather than one. The column-backed carriers ({@link ChildField.ColumnBackedField},
 * {@link ChildField.ColumnBackedReferenceField}) have the whole key tuple in scope and wrap it at
 * the SELECT-side projection. {@link ChildField.RecordReadField} is not column-backed at all: its
 * value arrives through one read, so an encode there applies to what the read yielded and the
 * carrier is admissible only for a node type whose key is a single column. That arity demand is a
 * fact about the read and is stated where the resolution is, not here.
 *
 * <ul>
 *   <li>{@link Direct} — plain SELECT-term projection. The column's value is the field's value;
 *       no wrapper is applied.</li>
 *   <li>{@link NodeIdEncodeKeys} — wrap the column(s) in the per-Node {@code encode<TypeName>}
 *       helper to produce a base64-encoded NodeId. The {@link HelperRef.Encode} reference
 *       carries both the helper class and the column shape so the emitter does not reconstruct
 *       names from the typeId at emission time.</li>
 * </ul>
 */
public sealed interface CallSiteCompaction permits CallSiteCompaction.Direct, CallSiteCompaction.NodeIdEncodeKeys {

    /** Plain SELECT-term projection — the column's value is the field's value. */
    record Direct() implements CallSiteCompaction {}

    /**
     * Wrap the column(s) in the per-Node {@code encode<TypeName>} helper.
     *
     * <p>{@code encodeMethod} is the pre-resolved {@link HelperRef.Encode} reference whose
     * {@code paramSignature} is positionally equal to the NodeType's {@code keyColumns}. The
     * emitter reads {@code encodeMethod.encoderClass()} and {@code encodeMethod.methodName()}
     * directly; no string typeId reconstruction at emission time.
     */
    record NodeIdEncodeKeys(HelperRef.Encode encodeMethod) implements CallSiteCompaction {}
}
