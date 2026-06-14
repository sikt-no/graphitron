package no.sikt.graphitron.lsp.completions;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import org.eclipse.lsp4j.Range;

/**
 * Per-cursor context handed to coordinate-driven completion providers.
 * Bundles the schema coordinate at the cursor with the LSP range each
 * provider must attach to its {@link org.eclipse.lsp4j.TextEdit}, so
 * clients replace the whole value (or partial value) at the cursor
 * rather than apply their own word-boundary heuristics to the candidate
 * label.
 *
 * <p>Hoisting both the coordinate and the range out of the providers
 * keeps the {@link LspVocabulary#locateAt} descent single-sourced; each
 * provider drops the {@code (vocab, directive, pos, source)} quad and
 * takes this record instead. {@link MethodCompletions} is the one
 * exception that still needs the wider tuple because it walks to a
 * sibling slot.
 *
 * <p>R307: also carries the enclosing directive name (from
 * {@link LspVocabulary.CursorLocation}) so a provider can discriminate by
 * directive when the coordinate is shared across directives, e.g.
 * {@link ClassNameCompletions} skips {@code @record}, whose
 * {@code ExternalCodeReference.className} coordinate is identical to
 * {@code @enum}'s.
 */
public record CompletionContext(SchemaCoordinate coordinate, Range replaceRange, String directiveName) {

    /**
     * Builds a context for {@code location}. The leaf node's tree-sitter
     * kind discriminates the slice: {@code string_value} strips its
     * surrounding delimiters (one byte either side for {@code "..."},
     * three bytes either side for {@code """..."""}); {@code enum_value}
     * and bare {@code name} use the full span. Empty literals
     * ({@code ""} or {@code """"""}) collapse to a zero-width range at
     * the delimiters' inside, which is also the cursor position.
     */
    public static CompletionContext from(LspVocabulary.CursorLocation location, byte[] source) {
        return new CompletionContext(
            location.coordinate(),
            replaceRangeFor(location.leafNode(), source),
            location.directiveName());
    }

    static Range replaceRangeFor(Node leaf, byte[] source) {
        int start = leaf.getStartByte();
        int end = leaf.getEndByte();
        if ("string_value".equals(leaf.getType())) {
            int length = end - start;
            // The bkegley GraphQL grammar surfaces both "..." and """..."""
            // as named kind "string_value"; discriminate by content.
            if (length >= 6
                && source[start] == '"' && source[start + 1] == '"' && source[start + 2] == '"'
                && source[end - 1] == '"' && source[end - 2] == '"' && source[end - 3] == '"') {
                return toRange(source, start + 3, end - 3);
            }
            if (length >= 2 && source[start] == '"' && source[end - 1] == '"') {
                return toRange(source, start + 1, end - 1);
            }
        }
        return toRange(source, start, end);
    }

    private static Range toRange(byte[] source, int startByte, int endByte) {
        return new Range(
            Positions.toLspPosition(source, startByte),
            Positions.toLspPosition(source, endByte));
    }
}
