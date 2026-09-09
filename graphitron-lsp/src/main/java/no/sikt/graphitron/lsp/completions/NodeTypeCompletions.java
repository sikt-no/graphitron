package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ENTRY;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_CONTAINER_MEMBER;

/**
 * Catalog GraphQL-type-name completions for any coordinate the
 * {@link LspVocabulary} overlay declares as a
 * {@link Behavior.NodeTypeBinding}: every GraphQL type in this graph whose SDL
 * carries {@code @node}, which is one row of {@code graphitron_node_entry} each,
 * plus every polymorphic container with at least one table-bound node-type
 * member, which a {@code @service} slot may name to take the id of any
 * implementation.
 *
 * <p>The container half reads {@code intent_node_container_member}, the same
 * relation the build's own resolution reads, so completion and the build move
 * together rather than one offering a value the other refuses. What the keyset
 * says is that a container is a legal <em>value</em> for {@code typeName:}; it
 * says nothing about whether the polymorphic rule reaches the coordinate the
 * directive was written at, which is the store's
 * {@code CONTAINER_NOT_AT_A_SLOT} verdict's answer and reaches the editor as a
 * diagnostic. Completing a container at a read-side argument and then
 * diagnosing it there is the division the incumbent family already runs.
 *
 * <p>Graph-keyed all the way down, so the scope is each relation's own
 * {@code graph_name} rather than a membership join: a {@code @node} declaration
 * and a container's membership are both facts about one graph's SDL, and a
 * sibling module's nodes are not candidates here however much of a store they
 * share.
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
        var items = new ArrayList<CompletionItem>();
        var nodes = store.dsl()
            .select(GRAPHITRON_NODE_ENTRY.TYPE_NAME, GRAPHITRON_NODE_ENTRY.TYPE_ID)
            .from(GRAPHITRON_NODE_ENTRY)
            .where(GRAPHITRON_NODE_ENTRY.GRAPH_NAME.eq(store.graphName()))
            .orderBy(GRAPHITRON_NODE_ENTRY.TYPE_NAME)
            .fetch();
        for (var row : nodes) {
            String typeId = row.value2();
            items.add(CompletionItems.replacing(
                row.value1(), CompletionItemKind.Class, context.replaceRange(),
                typeId != null ? "typeId: " + typeId : "@node"));
        }
        // The containers, each detailed with the implementations an id at such a slot may belong to,
        // which is the fact an author reaching for this value is choosing on. Table-bound node-type
        // members only, on the relation's own two flags: a member that binds no table has no record
        // to decode into and one that is not a node type refuses the whole container.
        var m = INTENT_NODE_CONTAINER_MEMBER;
        var containers = store.dsl()
            .select(m.CONTAINER_NAME, m.MEMBER_TYPE_NAME)
            .from(m)
            .where(m.GRAPH_NAME.eq(store.graphName()),
                m.IS_TABLE_BOUND.isTrue(), m.IS_NODE_TYPE.isTrue())
            .orderBy(m.CONTAINER_NAME, m.MEMBER_TYPE_NAME)
            .fetch();
        var membersByContainer = new LinkedHashMap<String, List<String>>();
        for (var row : containers) {
            membersByContainer.computeIfAbsent(row.value1(), k -> new ArrayList<>()).add(row.value2());
        }
        for (var entry : membersByContainer.entrySet()) {
            items.add(CompletionItems.replacing(
                entry.getKey(), CompletionItemKind.Interface, context.replaceRange(),
                "any of: " + String.join(", ", entry.getValue())));
        }
        return items;
    }
}
