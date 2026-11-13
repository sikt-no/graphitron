package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.facts.NodeContainerMember;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;

/**
 * Catalog GraphQL-type-name completions for any coordinate the
 * {@link LspVocabulary} overlay declares as a
 * {@link Behavior.NodeTypeBinding}: every node of this graph as
 * {@code graphitron_node} resolves it, a node by {@code @node} and one inferred
 * from published catalog metadata alike, detailed with the type id it answers
 * to, plus every polymorphic container with at least one node member, which a
 * {@code @service} slot may name to take the id of any implementation.
 *
 * <p>Both halves are what the diagnostic judges a written value by, the node
 * half {@code graphitron_node} and the container half {@code graphql_poly_member}
 * under {@link NodeContainerMember#tableBoundNodeType}, so completion and the
 * diagnostic move together rather than one offering a value the other refuses.
 * A container is offered as a legal <em>value</em> for {@code typeName:};
 * whether the polymorphic rule reaches the coordinate the directive was written
 * at is the store's {@code CONTAINER_NOT_AT_A_SLOT} verdict, which reaches the
 * editor as a diagnostic.
 *
 * <p>Graph-keyed all the way down, so the scope is each relation's own
 * {@code graph_name} rather than a membership join: a node and a container's
 * membership are both facts about one graph, and a sibling module's nodes are
 * not candidates here however much of a store they share.
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
        var n = GRAPHITRON_NODE;
        var nodes = store.dsl()
            .select(n.TYPE_NAME, n.TYPE_ID)
            .from(n)
            .where(n.GRAPH_NAME.eq(store.graphName()))
            .orderBy(n.TYPE_NAME)
            .fetch();
        for (var row : nodes) {
            items.add(CompletionItems.replacing(
                row.value1(), CompletionItemKind.Class, context.replaceRange(),
                "typeId: " + row.value2()));
        }
        // The containers, each detailed with the implementations an id at such a slot may belong to,
        // which is the fact an author reaching for this value is choosing on. Table-bound node-type
        // members only: a member that binds no table has no record to decode into, and one that is
        // not a node type has no id.
        var m = GRAPHQL_POLY_MEMBER;
        var containers = store.dsl()
            .select(m.CONTAINER_NAME, m.MEMBER_TYPE_NAME)
            .from(m)
            .where(m.GRAPH_NAME.eq(store.graphName()),
                NodeContainerMember.tableBoundNodeType(m.GRAPH_NAME, m.MEMBER_TYPE_NAME))
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
