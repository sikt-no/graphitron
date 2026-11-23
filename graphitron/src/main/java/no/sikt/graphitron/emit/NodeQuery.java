package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_SERVED_BY_ID;

/**
 * The node lookup's query: one row per node this subgraph serves by its id, which is what
 * {@code Query.node} and {@code Query.nodes} judge an id against. An id whose type id is one of these
 * nodes' is decoded against that node's key; any other type id names nothing this subgraph serves.
 *
 * <p>Driven by {@code graphitron_node_served_by_id}, the relation stating the population, so this query
 * restates no part of it. Each node is judged through its {@code decode<TypeName>} helper, which
 * {@link NodeIdEncoderQuery} has the encoder emit for every node whose schema publishes a
 * {@code Tables} class; a node served by id over a schema that publishes none cannot generate at all,
 * the entity dispatch having no class to name its columns through.
 */
public final class NodeQuery {

    /** One node served by id: its name and the wire type id its ids carry. */
    public record Node(String typeName, String typeId) {
        public Node {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(typeId, "typeId");
        }
    }

    private NodeQuery() {}

    /** The graph's nodes served by id, in type name order. */
    public static List<Node> read(StoreHandle store) {
        var s = GRAPHITRON_NODE_SERVED_BY_ID;
        var n = GRAPHITRON_NODE;
        return store.dsl()
            .select(s.TYPE_NAME, n.TYPE_ID)
            .from(s)
            .join(n).on(n.GRAPH_NAME.eq(s.GRAPH_NAME), n.TYPE_NAME.eq(s.TYPE_NAME))
            .where(s.GRAPH_NAME.eq(store.graphName()))
            .orderBy(s.TYPE_NAME)
            .fetch(r -> new Node(r.value1(), r.value2()));
    }
}
