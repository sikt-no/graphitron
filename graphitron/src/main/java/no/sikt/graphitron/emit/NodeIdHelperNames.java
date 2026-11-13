package no.sikt.graphitron.emit;

/**
 * The names on the generated {@code NodeIdEncoder} its callers name: a node's helpers,
 * {@code encode<TypeName>} and {@code decode<TypeName>}, named for the node type, and the exception
 * {@code decodeInto} throws. The one spelling the encoder emits under and every call site names, so
 * the two cannot disagree on a name.
 */
public final class NodeIdHelperNames {

    private NodeIdHelperNames() {}

    /** The nested exception {@code decodeInto} throws where a column it would write already disagrees. */
    public static final String COLUMN_CONFLICT = "ColumnConflict";

    /** The helper encoding a node's key values as its id. */
    public static String encode(String nodeTypeName) {
        return "encode" + nodeTypeName;
    }

    /** The helper decoding a node's id into a record of its key columns. */
    public static String decode(String nodeTypeName) {
        return "decode" + nodeTypeName;
    }
}
