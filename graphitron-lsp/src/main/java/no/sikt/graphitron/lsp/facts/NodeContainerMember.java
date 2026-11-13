package no.sikt.graphitron.lsp.facts;

import org.jooq.Condition;
import org.jooq.Field;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;

/**
 * Which members of a union or an interface make it a legal {@code @nodeId(typeName:)} value: a
 * member that binds a table, so an id has a record to decode into, and is a node type, so it has
 * an id at all. Read off {@code graphql_poly_member} by both the completion that offers a container
 * and the diagnostic that judges one, through this one condition so the two cannot disagree.
 */
public final class NodeContainerMember {

    private NodeContainerMember() {}

    /**
     * The member, in the caller's graph, is a node: a type in {@code graphitron_node}, which binds a
     * table by construction.
     */
    public static Condition tableBoundNodeType(Field<String> graphName, Field<String> memberTypeName) {
        return exists(selectOne().from(GRAPHITRON_NODE)
            .where(GRAPHITRON_NODE.GRAPH_NAME.eq(graphName),
                GRAPHITRON_NODE.TYPE_NAME.eq(memberTypeName)));
    }
}
