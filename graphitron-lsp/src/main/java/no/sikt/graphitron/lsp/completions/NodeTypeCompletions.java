package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
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
        CompletionContext context
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.NodeTypeBinding)) {
            return List.of();
        }
        var items = new ArrayList<CompletionItem>(data.nodeMetadata().size());
        for (var entry : data.nodeMetadata().entrySet()) {
            items.add(toCompletionItem(entry.getKey(), entry.getValue(), context));
        }
        return items;
    }

    private static CompletionItem toCompletionItem(
        String typeName, CompletionData.NodeMetadata meta, CompletionContext context
    ) {
        String detail = meta.typeId() != null ? "typeId: " + meta.typeId() : "@node";
        return CompletionItems.replacing(typeName, CompletionItemKind.Class, context.replaceRange(), detail);
    }
}
