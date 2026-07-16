package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;

/**
 * Tree-sitter node helpers. Pulled out so the directive code reads close
 * to its Rust counterpart.
 */
public final class Nodes {

    private Nodes() {}

    /**
     * Inclusive range containment. Tree-sitter ranges are half-open at the
     * end; the Rust LSP's {@code contains} treats both ends as inclusive
     * because cursor positions sit between characters and editing at
     * end-of-range should still resolve to the surrounding node.
     */
    public static boolean contains(Node node, Point point) {
        Point start = node.getStartPoint();
        Point end = node.getEndPoint();
        if (point.row() < start.row() || point.row() > end.row()) {
            return false;
        }
        if (point.row() == start.row() && point.column() < start.column()) {
            return false;
        }
        if (point.row() == end.row() && point.column() > end.column()) {
            return false;
        }
        return true;
    }

    /**
     * Slice of {@code source} corresponding to {@code node}. UTF-8 byte
     * offsets from tree-sitter map onto byte offsets in the source string;
     * we need the encoded bytes to slice safely.
     */
    public static String text(Node node, byte[] source) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        return new String(source, start, end - start, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Strips one layer of surrounding double quotes if present.
     */
    public static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * First direct child of {@code parent} whose kind is {@code kind}, or {@code null} if there is
     * none. Null-safe on {@code parent} and on the child slots tree-sitter may hand back as empty.
     *
     * <p>Consolidated from the 12 verbatim copies that had grown one-per-feature,
     * two of which ({@code Definitions}, {@code TypeContext}) had dropped the child null-guard and
     * could NPE. This is now the single navigation entry point; callers name the kind with a
     * {@link GraphqlNodeKind} constant rather than a bare string literal.
     */
    public static Node childOfKind(Node parent, GraphqlNodeKind kind) {
        if (parent == null) {
            return null;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = parent.getChild(i).orElse(null);
            if (kind.matches(child)) {
                return child;
            }
        }
        return null;
    }

    /** True iff {@code parent}'s byte range fully encloses {@code child}'s. */
    public static boolean nodeContains(Node parent, Node child) {
        return parent.getStartByte() <= child.getStartByte()
            && parent.getEndByte() >= child.getEndByte();
    }

    /**
     * Tree-sitter node identity: two {@link Node} handles obtained from different tree walks may
     * not be reference-equal even when they point at the same syntactic node. Compare by byte range
     * + grammar type instead, which uniquely identifies a node within a parse.
     */
    public static boolean sameNode(Node a, Node b) {
        return a.getStartByte() == b.getStartByte()
            && a.getEndByte() == b.getEndByte()
            && a.getType().equals(b.getType());
    }

    /**
     * Innermost {@code object_field} node that contains {@code pos}, or {@code null} if {@code pos}
     * falls outside {@code node} or inside no object field. Descends the whole subtree, preferring
     * the deepest match.
     */
    public static Node innermostObjectFieldContaining(Node node, Point pos) {
        if (node == null || !contains(node, pos)) {
            return null;
        }
        Node best = GraphqlNodeKind.OBJECT_FIELD.matches(node) ? node : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node descendant = innermostObjectFieldContaining(node.getChild(i).orElse(null), pos);
            if (descendant != null) {
                best = descendant;
            }
        }
        return best;
    }
}
