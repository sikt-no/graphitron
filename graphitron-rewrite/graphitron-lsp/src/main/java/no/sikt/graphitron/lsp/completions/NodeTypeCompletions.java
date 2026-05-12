package no.sikt.graphitron.lsp.completions;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Catalog GraphQL-type-name completions for any coordinate the
 * {@link LspVocabulary} overlay declares as a
 * {@link Behavior.NodeTypeBinding}. The candidate set is the keys of
 * {@link CompletionData#nodeMetadata()}: every GraphQL type whose SDL
 * carries {@code @node}.
 *
 * <p>Sibling-by-keyset to {@link FieldCompletions} and
 * {@link TableCompletions}: same dispatch shape, different keyset.
 */
public final class NodeTypeCompletions {

    private NodeTypeCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        CompletionData data,
        Directives.Directive directive,
        Point pos,
        byte[] source
    ) {
        var coord = vocabulary.coordinateAt(directive, pos, source);
        if (coord.isEmpty()) return List.of();
        var behavior = vocabulary.behaviorAt(coord.get());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.NodeTypeBinding)) {
            return List.of();
        }
        var items = new ArrayList<CompletionItem>(data.nodeMetadata().size());
        for (var entry : data.nodeMetadata().entrySet()) {
            items.add(toCompletionItem(entry.getKey(), entry.getValue()));
        }
        return items;
    }

    private static CompletionItem toCompletionItem(String typeName, CompletionData.NodeMetadata meta) {
        var item = new CompletionItem(typeName);
        item.setKind(CompletionItemKind.Class);
        String detail = meta.typeId() != null ? "typeId: " + meta.typeId() : "@node";
        item.setDetail(detail);
        return item;
    }
}
