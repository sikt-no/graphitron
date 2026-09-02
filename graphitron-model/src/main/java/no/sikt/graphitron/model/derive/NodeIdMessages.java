package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_COLUMN;

/**
 * The vocabulary the {@code @nodeId} rejection families share, held once so the two cannot drift.
 * Two families refuse the same two facts at two carriers, an {@code argMapping} entry binding a node
 * id and a producer parameter receiving one by name, and an author who moves a binding from one form
 * to the other should meet the same words for the same fault. Held here rather than as copied text
 * because copied text is what drifts: a wording improved on one side and not the other reads as two
 * different rules.
 *
 * <p>Only the vocabulary is shared. Which verdict fires, what remedy each offers, and how the entry
 * or the parameter is named are each family's own, those being the parts that genuinely differ.
 */
final class NodeIdMessages {

    private NodeIdMessages() {}

    /** {@code @nodeId(typeName: "X")} where the author named a type, {@code @nodeId} where not. */
    static String nodeIdSpelling(String nodeTypeRef) {
        return nodeTypeRef == null ? "@nodeId" : "@nodeId(typeName: \"" + nodeTypeRef + "\")";
    }

    /**
     * The trailing name of a fully qualified Java type. Types are qualified in the store because
     * that is what makes them comparable; a message reads better unqualified, and two types that
     * differ only in package are the rarer case than two that differ in name.
     */
    static String simpleName(String javaType) {
        if (javaType == null) {
            return "an unresolved type";
        }
        int dot = javaType.lastIndexOf('.');
        return dot < 0 ? javaType : javaType.substring(dot + 1);
    }

    /**
     * The node type's resolved key columns, in key order: the candidate list a message offers, read
     * as rows off the relation that resolved them rather than as a render some view joined and a
     * consumer had to split apart. Empty where the type is unnamed and where no tier answered for
     * it, which are two different facts the caller tells apart by {@code nodeTypeRef}.
     */
    static List<String> keyColumnsOf(DSLContext dsl, String graphName, String nodeTypeRef) {
        if (nodeTypeRef == null) {
            return List.of();
        }
        var k = INTENT_RESOLVED_NODE_KEY_COLUMN;
        return dsl.select(k.COLUMN_NAME)
            .from(k)
            .where(k.GRAPH_NAME.eq(graphName), k.TYPE_NAME.eq(nodeTypeRef))
            .orderBy(k.POSITION)
            .fetch(r -> r.value1());
    }
}
