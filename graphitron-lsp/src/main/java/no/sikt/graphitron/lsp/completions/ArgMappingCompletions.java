package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.ArgMapping;
import no.sikt.graphitron.lsp.parsing.ArgMappingSupport;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.model.read.StoreHandle;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.List;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.STRING_VALUE;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;

/**
 * Completion inside an {@code argMapping} string literal
 * ({@code "javaParam: graphqlArg, ..."}). The string-content cursor is
 * decomposed by {@link ArgMapping} into which entry and side it sits on; the
 * candidates then depend on the side:
 *
 * <ul>
 *   <li><b>Left</b> (Java parameter): the parameter names of the method the sibling
 *       {@code className} / {@code method} values name, from {@code jvm_method_parameter}.
 *       Suppressed when the names are absent (the consumer compiled without
 *       {@code -parameters}); an existing diagnostic nudges toward the fix.</li>
 *   <li><b>Right</b> (GraphQL argument): the enclosing field's GraphQL argument
 *       names, read syntactically from the {@code field_definition}. Dot-path
 *       expansion into nested input fields is deferred (the LSP carries
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
        StoreHandle store,
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

        int quote = CompletionContext.openingQuoteLength(source, leaf.getStartByte(), leaf.getEndByte());
        if (quote == 0) return List.of();
        String raw = Nodes.text(leaf, source);
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
            case LEFT -> leftCandidates(vocabulary, store, directive, context.coordinate(), pos, source, replaceRange);
            case RIGHT -> rightCandidates(directive, cursor.token().text(), source, replaceRange);
        };
    }

    /**
     * The parameter names of the named method, across every overload of it. The schema names a method
     * by name alone, so which overload an author meant is not something the census can answer and the
     * projection's first-match pick was a silent choice; the union, deduplicated by name and ordered
     * by descriptor then position, offers every name that could be right. A method the walk never met
     * has no rows and completes nothing.
     */
    private static List<CompletionItem> leftCandidates(
        LspVocabulary vocabulary, StoreHandle store, Directives.Directive directive,
        no.sikt.graphitron.lsp.parsing.SchemaCoordinate coord, Point pos, byte[] source, Range replaceRange
    ) {
        var target = ArgMappingSupport.siblingMethodTarget(vocabulary, directive, pos, coord, source);
        if (target.isEmpty()) return List.of();
        var names = store.dsl()
            .select(JVM_METHOD_PARAMETER.PARAMETER_NAME)
            .from(JVM_METHOD_PARAMETER)
            .where(store.reads(JVM_METHOD_PARAMETER.SOURCE_NAME))
            .and(JVM_METHOD_PARAMETER.CLASS_NAME.eq(target.get().className()))
            .and(JVM_METHOD_PARAMETER.METHOD_NAME.eq(target.get().methodName()))
            .and(JVM_METHOD_PARAMETER.PARAMETER_NAME.isNotNull())
            .orderBy(JVM_METHOD_PARAMETER.DESCRIPTOR, JVM_METHOD_PARAMETER.POSITION)
            .fetch(JVM_METHOD_PARAMETER.PARAMETER_NAME);
        return names.stream().distinct()
            .filter(name -> !name.isEmpty())
            .map(name -> CompletionItems.replacing(name, CompletionItemKind.Variable, replaceRange))
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
            .map(name -> CompletionItems.replacing(name, CompletionItemKind.Field, replaceRange))
            .toList();
    }

    private static Range rangeFor(byte[] source, int contentStartByte, ArgMapping.Segment token) {
        int startByte = contentStartByte + token.start();
        int endByte = contentStartByte + token.end();
        return new Range(
            Positions.toLspPosition(source, startByte),
            Positions.toLspPosition(source, endByte));
    }
}
