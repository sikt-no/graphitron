package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_EDGE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_POSITION;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

/**
 * The generated connection classes' query: one row per connection type and one per edge type of the
 * graph, each with the positions of the connection shape its fields fill, as
 * {@code graphitron_field_position} states them. A class has a member for exactly the positions its
 * type fills, so an authored connection declaring no {@code totalCount} has no count member and a
 * field an author added to the type fills none.
 *
 * <p>A connection names the edge it pages through ({@code graphitron_connection}), and where it fills
 * {@code FACETS}, the facets type its facets field names, whose record {@code util.Facets} holds.
 * An edge two connections share is one row.
 */
public final class ConnectionsQuery {

    /** A connection type: its name, its edge type, the positions it fills, and its facets type or null. */
    public record Connection(String typeName, String edgeTypeName, Set<String> positions, String facetsTypeName) {
        public Connection {
            positions = Set.copyOf(positions);
        }
    }

    /** An edge type: its name and the positions it fills. */
    public record Edge(String typeName, Set<String> positions) {
        public Edge {
            positions = Set.copyOf(positions);
        }
    }

    /** The graph's connection and edge types, each in type name order. */
    public record Shapes(List<Connection> connections, List<Edge> edges) {
        public Shapes {
            connections = List.copyOf(connections);
            edges = List.copyOf(edges);
        }
    }

    private ConnectionsQuery() {}

    public static Shapes read(StoreHandle store) {
        var c = GRAPHITRON_CONNECTION;
        var e = GRAPHITRON_EDGE;
        var p = GRAPHITRON_FIELD_POSITION;
        var f = GRAPHITRON_FIELD;
        var dsl = store.dsl();
        var connections = dsl
            .select(c.TYPE_NAME, c.EDGE_TYPE_NAME,
                multiset(select(p.POSITION).from(p)
                    .where(p.GRAPH_NAME.eq(c.GRAPH_NAME), p.TYPE_NAME.eq(c.TYPE_NAME)))
                    .convertFrom(r -> Set.copyOf(r.map(x -> x.value1()))),
                field(select(f.NAMED_TYPE).from(p)
                    .join(f).on(f.GRAPH_NAME.eq(p.GRAPH_NAME), f.TYPE_NAME.eq(p.TYPE_NAME),
                        f.FIELD_NAME.eq(p.FIELD_NAME))
                    .where(p.GRAPH_NAME.eq(c.GRAPH_NAME), p.TYPE_NAME.eq(c.TYPE_NAME),
                        p.POSITION.eq("FACETS"))))
            .from(c)
            .where(c.GRAPH_NAME.eq(store.graphName()))
            .orderBy(c.TYPE_NAME)
            .fetch(r -> new Connection(r.value1(), r.value2(), r.value3(), r.value4()));
        var edges = dsl
            .select(e.TYPE_NAME,
                multiset(select(p.POSITION).from(p)
                    .where(p.GRAPH_NAME.eq(e.GRAPH_NAME), p.TYPE_NAME.eq(e.TYPE_NAME)))
                    .convertFrom(r -> Set.copyOf(r.map(x -> x.value1()))))
            .from(e)
            .where(e.GRAPH_NAME.eq(store.graphName()))
            .orderBy(e.TYPE_NAME)
            .fetch(r -> new Edge(r.value1(), r.value2()));
        return new Shapes(connections, edges);
    }
}
