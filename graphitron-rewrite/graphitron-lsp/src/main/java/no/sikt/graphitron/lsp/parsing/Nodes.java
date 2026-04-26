package no.sikt.graphitron.lsp.parsing;

import org.treesitter.TSNode;
import org.treesitter.TSPoint;

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
    public static boolean contains(TSNode node, TSPoint point) {
        TSPoint start = node.getStartPoint();
        TSPoint end = node.getEndPoint();
        if (point.getRow() < start.getRow() || point.getRow() > end.getRow()) {
            return false;
        }
        if (point.getRow() == start.getRow() && point.getColumn() < start.getColumn()) {
            return false;
        }
        if (point.getRow() == end.getRow() && point.getColumn() > end.getColumn()) {
            return false;
        }
        return true;
    }

    /**
     * Slice of {@code source} corresponding to {@code node}. UTF-8 byte
     * offsets from tree-sitter map onto byte offsets in the source string;
     * we need the encoded bytes to slice safely.
     */
    public static String text(TSNode node, byte[] source) {
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
}
