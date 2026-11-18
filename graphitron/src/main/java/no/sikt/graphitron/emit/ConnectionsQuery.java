package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_EDGE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FACET_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_POSITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_FACET;
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
 * <p>{@link #byField} reads what fills each connection at the grain of the field that returns it,
 * which is what a producer building the class needs: the class it names, whether it fills a count and
 * facets, and the facets it surfaces from its own filter arguments. What fills a connection is a fact
 * of the producing occurrence ({@code graphitron_occurrence_producer_connection} and
 * {@code graphitron_occurrence_producer_facet}); the producers render per declared field, so this
 * collapses a field's occurrences onto it and fails where they disagree rather than choosing one.
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
     * One facet a producer surfaces, in its place: the argument its filter value rides in, the facet's
     * name, the column its counts group by as written, the value type and whether a value may be null,
     * and whether a {@code @reference} puts the column on the table a path ends at.
     */
    public record Facet(String filterArgumentName, String name, String columnName, String valueTypeName,
            boolean valueNullable, boolean referenceBound) {}

    /**
     * A field returning a connection: the field's coordinate, the connection type it returns, which
     * names the class its producer builds, whether the producer fills a count and whether it fills
     * facets, and the facets it surfaces in component order, empty where it fills none.
     */
    public record FieldConnection(String parentTypeName, String fieldName, String connectionTypeName,
            boolean counts, boolean faceted, List<Facet> facets) {
        public FieldConnection {
            facets = List.copyOf(facets);
        }

        /** A field filling no facets. */
        public FieldConnection(String parentTypeName, String fieldName, String connectionTypeName,
                boolean counts, boolean faceted) {
            this(parentTypeName, fieldName, connectionTypeName, counts, faceted, List.of());
        }
    }

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
     * Every field of the graph returning a connection, with what its producing occurrences fill: the
     * connection type, whether they count, whether they carry facets and the facets they surface. A
     * field reached at several places has an occurrence per place; they are collapsed onto the field,
     * which is what a producer renders, and occurrences of one field that disagree are a generator bug
     * rather than a choice to make here.
     */
    public static FieldConnections byField(StoreHandle store) {
        var c = GRAPHITRON_OCCURRENCE_PRODUCER_CONNECTION;
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var pf = GRAPHITRON_OCCURRENCE_PRODUCER_FACET;
        var b = GRAPHITRON_FACET_BINDING;
        var rows = store.dsl()
            .select(c.COORDINATE, f.TYPE_NAME, f.FIELD_NAME, c.CONNECTION_TYPE_NAME, c.COUNTS, c.FACETED,
                multiset(select(pf.FILTER_ARGUMENT_NAME, pf.FACET_NAME, b.COLUMN_NAME, b.VALUE_TYPE_NAME,
                        b.VALUE_NULLABLE, b.REFERENCE_BOUND)
                    .from(pf)
                    .join(b).on(b.GRAPH_NAME.eq(pf.GRAPH_NAME), b.TYPE_NAME.eq(pf.FACET_TYPE_NAME),
                        b.FIELD_NAME.eq(pf.FACET_NAME))
                    .where(pf.GRAPH_NAME.eq(c.GRAPH_NAME), pf.COORDINATE.eq(c.COORDINATE))
                    .orderBy(pf.POSITION))
                    .convertFrom(r -> r.map(x -> new Facet(x.value1(), x.value2(), x.value3(), x.value4(),
                        x.value5(), x.value6()))))
            .from(c)
            .join(o).on(o.GRAPH_NAME.eq(c.GRAPH_NAME), o.COORDINATE.eq(c.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .where(c.GRAPH_NAME.eq(store.graphName()))
            .orderBy(c.COORDINATE)
            .fetch();
        var byCoordinate = new TreeMap<String, FieldConnection>();
        for (var r : rows) {
            var row = new FieldConnection(r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7());
            var key = row.parentTypeName() + "." + row.fieldName();
            var held = byCoordinate.putIfAbsent(key, row);
            if (held != null && !held.equals(row)) {
                throw new IllegalStateException("Graphitron generator bug: the occurrences of connection field '"
                    + key + "' disagree on what fills the connection, at " + r.value1() + ": " + held
                    + " against " + row);
            }
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
