package no.sikt.graphitron.lsp.facts;

import org.jooq.Condition;
import org.jooq.Field;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
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

    /** The member, in the caller's graph, binds a table and is a node type. */
    public static Condition tableBoundNodeType(Field<String> graphName, Field<String> memberTypeName) {
        return exists(selectOne().from(GRAPHITRON_TABLETYPE)
                .where(GRAPHITRON_TABLETYPE.GRAPH_NAME.eq(graphName),
                    GRAPHITRON_TABLETYPE.TYPE_NAME.eq(memberTypeName)))
            .and(exists(selectOne().from(GRAPHITRON_NODE_TYPE)
                .where(GRAPHITRON_NODE_TYPE.GRAPH_NAME.eq(graphName),
                    GRAPHITRON_NODE_TYPE.TYPE_NAME.eq(memberTypeName))));
    }
}
