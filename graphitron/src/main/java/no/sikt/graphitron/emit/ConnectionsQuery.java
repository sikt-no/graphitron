package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_EDGE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_POSITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

/**
 * The generated connection classes' query: one row per connection type and one per edge type of the
 * graph, each with the fields that fill a position of the connection shape and the position each
 * fills, as {@code graphitron_field_position} states them. A class has a member for exactly the
 * positions its type fills, so an authored connection declaring no {@code totalCount} has no count
 * member and a field an author added to the type fills none; a field is registered to the member
 * of its position under its own name.
 *
 * <p>A connection names the edge it pages through ({@code graphitron_connection}), and where it fills
 * {@code FACETS}, the facets type its facets field names, whose record {@code util.Facets} holds.
 * An edge two connections share is one row.
 *
 * <p>{@link #byField} reads the same facts at the grain of the field that returns a connection, which
 * is what a producer building the class needs: the class it names and whether its type counts and
 * carries facets, so the constructor it calls takes a count exactly where the class has a count
 * member and facets exactly where it has a facets member. Both are the positions the type fills, read
 * here off the field's wrapper rather than restated per field: the class and its producers read one
 * fact.
 */
public final class ConnectionsQuery {

    /**
     * A connection type: its name, its edge type, the position each of its position-filling fields
     * fills keyed by the field's name, and its facets type or null.
     */
    public record Connection(String typeName, String edgeTypeName, Map<String, String> fieldPositions,
            String facetsTypeName) {
        public Connection {
            fieldPositions = java.util.Collections.unmodifiableMap(new TreeMap<>(fieldPositions));
        }

        /** The positions the type fills. */
        public Set<String> positions() {
            return Set.copyOf(fieldPositions.values());
        }
    }

    /** An edge type: its name and the position each of its position-filling fields fills, by field name. */
    public record Edge(String typeName, Map<String, String> fieldPositions) {
        public Edge {
            fieldPositions = java.util.Collections.unmodifiableMap(new TreeMap<>(fieldPositions));
        }

        /** The positions the type fills. */
        public Set<String> positions() {
            return Set.copyOf(fieldPositions.values());
        }
    }

    /** The graph's connection and edge types, each in type name order. */
    public record Shapes(List<Connection> connections, List<Edge> edges) {
        public Shapes {
            connections = List.copyOf(connections);
            edges = List.copyOf(edges);
        }
    }

    /**
     * A field returning a connection: the field's coordinate, the connection type it returns, which
     * names the class its producer builds, whether that class counts what it pages through (its type
     * fills {@code TOTAL_COUNT}) and whether it carries facets (its type fills {@code FACETS}).
     */
    public record FieldConnection(String parentTypeName, String fieldName, String connectionTypeName,
            boolean counts, boolean faceted) {}

    /** The graph's connection-returning fields, keyed by coordinate ({@code Type.field}). */
    public record FieldConnections(Map<String, FieldConnection> byCoordinate) {
        public FieldConnections {
            byCoordinate = java.util.Collections.unmodifiableMap(new TreeMap<>(byCoordinate));
        }

        /** No connection-returning field: the answer for a caller with no store to read. */
        public static FieldConnections empty() {
            return new FieldConnections(Map.of());
        }

        /**
         * The connection the field at the coordinate returns. A connection field the store states no
         * row for is a generator bug, never a name to guess.
         */
        public FieldConnection at(String parentTypeName, String fieldName) {
            var found = byCoordinate.get(parentTypeName + "." + fieldName);
            if (found == null) {
                throw new IllegalStateException("Graphitron generator bug: connection field '" + parentTypeName
                    + "." + fieldName + "' has no connection type in the store to build");
            }
            return found;
        }
    }

    private ConnectionsQuery() {}

    /**
     * Every field of the graph returning a connection, with the connection type it returns and
     * ({@code graphitron_field_target}'s wrapper) and whether that type fills {@code TOTAL_COUNT} and
     * {@code FACETS}.
     */
    public static FieldConnections byField(StoreHandle store) {
        var t = GRAPHITRON_FIELD_TARGET;
        var p = GRAPHITRON_FIELD_POSITION;
        var rows = store.dsl()
            .select(t.TYPE_NAME, t.FIELD_NAME, t.WRAPPER_TYPE_NAME,
                field(exists(select(p.POSITION).from(p).where(p.GRAPH_NAME.eq(t.GRAPH_NAME),
                    p.TYPE_NAME.eq(t.WRAPPER_TYPE_NAME), p.POSITION.eq("TOTAL_COUNT")))),
                field(exists(select(p.POSITION).from(p).where(p.GRAPH_NAME.eq(t.GRAPH_NAME),
                    p.TYPE_NAME.eq(t.WRAPPER_TYPE_NAME), p.POSITION.eq("FACETS")))))
            .from(t)
            .where(t.GRAPH_NAME.eq(store.graphName()), t.WRAPPER_TYPE_NAME.isNotNull())
            .fetch(r -> new FieldConnection(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
        var byCoordinate = new TreeMap<String, FieldConnection>();
        for (var row : rows) {
            byCoordinate.put(row.parentTypeName() + "." + row.fieldName(), row);
        }
        return new FieldConnections(byCoordinate);
    }

    public static Shapes read(StoreHandle store) {
        var c = GRAPHITRON_CONNECTION;
        var e = GRAPHITRON_EDGE;
        var p = GRAPHITRON_FIELD_POSITION;
        var f = GRAPHITRON_FIELD;
        var dsl = store.dsl();
        var connections = dsl
            .select(c.TYPE_NAME, c.EDGE_TYPE_NAME,
                multiset(select(p.FIELD_NAME, p.POSITION).from(p)
                    .where(p.GRAPH_NAME.eq(c.GRAPH_NAME), p.TYPE_NAME.eq(c.TYPE_NAME)))
                    .convertFrom(r -> r.intoMap(x -> x.value1(), x -> x.value2())),
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
                multiset(select(p.FIELD_NAME, p.POSITION).from(p)
                    .where(p.GRAPH_NAME.eq(e.GRAPH_NAME), p.TYPE_NAME.eq(e.TYPE_NAME)))
                    .convertFrom(r -> r.intoMap(x -> x.value1(), x -> x.value2())))
            .from(e)
            .where(e.GRAPH_NAME.eq(store.graphName()))
            .orderBy(e.TYPE_NAME)
            .fetch(r -> new Edge(r.value1(), r.value2()));
        return new Shapes(connections, edges);
    }
}
