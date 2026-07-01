package no.sikt.graphitron.lsp.completions;

import io.github.treesitter.jtreesitter.Node;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import org.eclipse.lsp4j.Range;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.STRING_VALUE;

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
        if (STRING_VALUE.matches(leaf)) {
            int quote = openingQuoteLength(source, start, end);
            if (quote > 0) {
                return toRange(source, start + quote, end - quote);
            }
        }
        return toRange(source, start, end);
    }

    /**
     * Delimiter width of the GraphQL string literal spanning {@code [start, end)}
     * in {@code source}: 3 for a triple-quoted block string, 1 for an ordinary
     * double-quoted string, 0 when the span is not a closed double-quoted literal
     * (an unterminated or non-string leaf). The bkegley grammar surfaces both
     * {@code "..."} and {@code """..."""} as one {@code string_value} kind, so
     * the discrimination is by content, not node kind. Shared with
     * {@link ArgMappingCompletions}, which strips the same delimiters off an
     * {@code argMapping} literal before parsing its content.
     */
    static int openingQuoteLength(byte[] source, int start, int end) {
        int length = end - start;
        if (length >= 6
            && source[start] == '"' && source[start + 1] == '"' && source[start + 2] == '"'
            && source[end - 1] == '"' && source[end - 2] == '"' && source[end - 3] == '"') {
            return 3;
        }
        if (length >= 2 && source[start] == '"' && source[end - 1] == '"') {
            return 1;
        }
        return 0;
    }

    private static Range toRange(byte[] source, int startByte, int endByte) {
        return new Range(
            Positions.toLspPosition(source, startByte),
            Positions.toLspPosition(source, endByte));
    }
}
