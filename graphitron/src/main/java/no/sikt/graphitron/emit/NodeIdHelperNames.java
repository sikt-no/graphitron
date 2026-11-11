package no.sikt.graphitron.emit;

/**
 * The names of a node's helpers on the generated {@code NodeIdEncoder}: {@code encode<TypeName>} and
 * {@code decode<TypeName>}, named for the node type. The one spelling the encoder emits under and every
 * call site names, so the two cannot disagree on a name.
 */
public final class NodeIdHelperNames {

    private NodeIdHelperNames() {}

    /** The helper encoding a node's key values as its id. */
    public static String encode(String nodeTypeName) {
        return "encode" + nodeTypeName;
    }

    /** The helper decoding a node's id into a record of its key columns. */
    public static String decode(String nodeTypeName) {
        return "decode" + nodeTypeName;
    }
}
