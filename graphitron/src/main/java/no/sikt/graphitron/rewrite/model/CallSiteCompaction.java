package no.sikt.graphitron.rewrite.model;

/**
 * How the read's value becomes the wire value.
 *
 * <p>Symmetric counterpart to {@link CallSiteExtraction}: extraction reads a wire value into a
 * typed Java value at the call site; compaction writes a typed Java value back into a wire value
 * at the read. Both classify exhaustively at the parse boundary.
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
 *   <li>{@link EnumKnownOrNull} — the read is one text value of a GraphQL enum, passed through
 *       the enum's generated {@code knownOrNull} helper so a database value the enum does not
 *       name resolves to {@code null} instead of a serialization error.</li>
 *   <li>{@link NodeIdEncodeKeys} — wrap the column(s) in the per-Node {@code encode<TypeName>}
 *       helper to produce a base64-encoded NodeId. The {@link HelperRef.Encode} reference
 *       carries both the helper class and the column shape so the emitter does not reconstruct
 *       names from the typeId at emission time.</li>
 * </ul>
 *
 * <p>The first two are {@link PlainValue}s: the projection is the plain column and the read
 * yields one value. Read sites switch over {@link PlainValue} and {@link NodeIdEncodeKeys} with
 * no default, so a new arm is a compile error at each of them rather than a silent fall-through
 * to today's emission.
 */
public sealed interface CallSiteCompaction permits CallSiteCompaction.PlainValue, CallSiteCompaction.NodeIdEncodeKeys {

    /**
     * The arms whose projection is the plain column and whose read yields one value, which the
     * read may pass through a single-argument wrapper before it reaches the wire.
     */
    sealed interface PlainValue extends CallSiteCompaction permits Direct, EnumKnownOrNull {}

    /** Plain SELECT-term projection — the column's value is the field's value. */
    record Direct() implements PlainValue {}

    /**
     * Pass the read through the enum's generated {@code knownOrNull} helper, which returns the
     * value when the enum names it and {@code null} otherwise. Chosen at classification for a
     * single-valued enum field read from a text column, where the column can hold values the
     * enum does not map.
     */
    record EnumKnownOrNull(KnownOrNullRef helper) implements PlainValue {
        public EnumKnownOrNull {
            java.util.Objects.requireNonNull(helper, "helper");
        }
    }

    /**
     * The generated helper an {@link EnumKnownOrNull} read calls: {@code hostClassFqcn} names the
     * enum's {@code <Name>Type} schema-shape class, {@code methodName} the static generic
     * {@code <T> T m(T value, String coordinate)} method on it. A name rather than an emit-library
     * class handle, which the renderer builds.
     */
    record KnownOrNullRef(String hostClassFqcn, String methodName) {
        public KnownOrNullRef {
            java.util.Objects.requireNonNull(hostClassFqcn, "hostClassFqcn");
            java.util.Objects.requireNonNull(methodName, "methodName");
        }

        /** The host class's simple name, the part after the last package separator. */
        public String hostSimpleName() {
            return hostClassFqcn.substring(hostClassFqcn.lastIndexOf('.') + 1);
        }
    }

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
