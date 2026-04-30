package no.sikt.graphitron.rewrite.model;

/**
 * How to write a typed Java value back into a wire-format value at a SELECT-side projection.
 *
 * <p>Symmetric counterpart to {@link CallSiteExtraction}: extraction reads a wire value into a
 * typed Java value at the call site; compaction writes a typed Java value back into a wire value
 * at the projection site. Both classify exhaustively at the parse boundary.
 *
 * <p>Carried by single-column output carriers ({@link ChildField.ColumnField},
 * {@link ChildField.ColumnReferenceField}) where both arms genuinely occur, and by composite
 * output carriers ({@link ChildField.CompositeColumnField},
 * {@link ChildField.CompositeColumnReferenceField}) narrowed to {@link NodeIdEncodeKeys} since
 * there is no plain composite-column projection. The projection emitter switches on the slot's
 * sealed arm to decide how to wrap the carrier's column(s).
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
