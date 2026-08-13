package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;

/**
 * Catalog GraphQL-type-name completions for any coordinate the
 * {@link LspVocabulary} overlay declares as a
 * {@link Behavior.NodeTypeBinding}: every GraphQL type in this graph whose SDL
 * carries {@code @node}, which is one row of {@code graphitron_node} each.
 *
 * <p>Graph-keyed all the way down, so the scope is the relation's own
 * {@code graph_name} rather than a membership join: a {@code @node} declaration
 * is a fact about one graph's SDL, and a sibling module's nodes are not
 * candidates here however much of a store they share.
 */
public final class NodeTypeCompletions {

    private NodeTypeCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        StoreHandle store,
        CompletionContext context
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.NodeTypeBinding)) {
            return List.of();
        }
        var rows = store.dsl()
            .select(GRAPHITRON_NODE.TYPE_NAME, GRAPHITRON_NODE.TYPE_ID)
            .from(GRAPHITRON_NODE)
            .where(GRAPHITRON_NODE.GRAPH_NAME.eq(store.graphName()))
            .orderBy(GRAPHITRON_NODE.TYPE_NAME)
            .fetch();
        var items = new ArrayList<CompletionItem>(rows.size());
        for (var row : rows) {
            String typeId = row.value2();
            items.add(CompletionItems.replacing(
                row.value1(), CompletionItemKind.Class, context.replaceRange(),
                typeId != null ? "typeId: " + typeId : "@node"));
        }
        return items;
    }
}
