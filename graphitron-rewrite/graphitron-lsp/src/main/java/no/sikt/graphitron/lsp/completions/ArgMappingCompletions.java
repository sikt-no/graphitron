package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.ArgMapping;
import no.sikt.graphitron.lsp.parsing.ArgMappingSupport;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.STRING_VALUE;

/**
 * Completion inside an {@code argMapping} string literal
 * ({@code "javaParam: graphqlArg, ..."}). The string-content cursor is
 * decomposed by {@link ArgMapping} into which entry and side it sits on; the
 * candidates then depend on the side:
 *
 * <ul>
 *   <li><b>Left</b> (Java parameter): the resolved method's parameter names,
 *       read off the catalog. Suppressed when the names are {@code null} (the
 *       consumer compiled without {@code -parameters}); the existing 5c
 *       diagnostic nudges toward the fix.</li>
 *   <li><b>Right</b> (GraphQL argument): the enclosing field's GraphQL argument
 *       names, read syntactically from the {@code field_definition}. R84
 *       dot-path expansion into nested input fields is deferred (the LSP carries
 *       no projection of arbitrary input-type field trees); a right token that
 *       already contains a {@code .} yields no candidates rather than a wrong
 *       flat list.</li>
 * </ul>
 *
 * <p>Keyed on {@link Behavior.ArgMappingBinding}; inert at every other
 * coordinate. The replace range targets the token under the cursor (or a
 * zero-width span at the caret on an empty side), so the client replaces the
 * partial token rather than the whole string.
 */
public final class ArgMappingCompletions {

    private ArgMappingCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        CompletionData data,
        CompletionContext context,
        Directives.Directive directive,
        Point pos,
        Position lspPos,
        byte[] source
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.ArgMappingBinding)) {
            return List.of();
        }
        var locationOpt = vocabulary.locateAt(directive, pos, source);
        if (locationOpt.isEmpty()) return List.of();
        Node leaf = locationOpt.get().leafNode();
        if (!STRING_VALUE.matches(leaf)) return List.of();

        String raw = Nodes.text(leaf, source);
        int quote = openingQuoteLength(raw);
        if (raw.length() < quote * 2) return List.of();
        String content = raw.substring(quote, raw.length() - quote);
        int contentStartByte = leaf.getStartByte() + quote;

        int cursorByte = Positions.resolve(source, lspPos.getLine(), lspPos.getCharacter()).byteOffset();
        int offset = cursorByte - contentStartByte;
        if (offset < 0 || offset > content.length()) return List.of();

        var cursorOpt = ArgMapping.locate(content, offset);
        if (cursorOpt.isEmpty()) return List.of();
        var cursor = cursorOpt.get();
        Range replaceRange = rangeFor(source, contentStartByte, cursor.token());

        return switch (cursor.side()) {
            case LEFT -> leftCandidates(vocabulary, data, directive, context.coordinate(), pos, source, replaceRange);
            case RIGHT -> rightCandidates(directive, cursor.token().text(), source, replaceRange);
        };
    }

    private static List<CompletionItem> leftCandidates(
        LspVocabulary vocabulary, CompletionData data, Directives.Directive directive,
        no.sikt.graphitron.lsp.parsing.SchemaCoordinate coord, Point pos, byte[] source, Range replaceRange
    ) {
        var method = ArgMappingSupport.resolveMethod(vocabulary, directive, pos, coord, data, source);
        if (method.isEmpty()) return List.of();
        return method.get().parameters().stream()
            .map(CompletionData.Parameter::name)
            .filter(name -> name != null && !name.isEmpty())
            .map(name -> item(name, CompletionItemKind.Variable, replaceRange))
            .toList();
    }

    private static List<CompletionItem> rightCandidates(
        Directives.Directive directive, String token, byte[] source, Range replaceRange
    ) {
        // Dot-path expansion into nested input fields is not modelled; offer
        // nothing rather than a misleading flat list once a '.' is present.
        if (token.indexOf('.') >= 0) return List.of();
        var fieldDef = TypeContext.enclosingFieldDefinition(directive.outer());
        if (fieldDef.isEmpty()) return List.of();
        return TypeContext.fieldArgumentNames(fieldDef.get(), source).stream()
            .map(name -> item(name, CompletionItemKind.Field, replaceRange))
            .toList();
    }

    private static CompletionItem item(String label, CompletionItemKind kind, Range replaceRange) {
        var item = new CompletionItem(label);
        item.setKind(kind);
        item.setTextEdit(Either.forLeft(new TextEdit(replaceRange, label)));
        return item;
    }

    private static Range rangeFor(byte[] source, int contentStartByte, ArgMapping.Segment token) {
        int startByte = contentStartByte + token.start();
        int endByte = contentStartByte + token.end();
        return new Range(
            Positions.toLspPosition(source, startByte),
            Positions.toLspPosition(source, endByte));
    }

    private static int openingQuoteLength(String raw) {
        if (raw.length() >= 6 && raw.startsWith("\"\"\"") && raw.endsWith("\"\"\"")) return 3;
        return 1;
    }
}
