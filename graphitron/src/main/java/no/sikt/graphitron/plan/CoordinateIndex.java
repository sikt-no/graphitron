package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The row set of a command relation whose key is the coordinate alone: the rows in producer
 * order, the key rejection, and the per-coordinate lookup, declared once for every relation that
 * has that key rather than once per relation.
 *
 * <p>Three relations carry this key today ({@link LauncherRelation},
 * {@link RoutineWriteRelation}, {@link FetcherEdgeRelation}) and each had restated the same
 * duplicate-key loop with its own message. A fourth would have restated it again, which is what
 * makes this a carrier rather than a helper: the key is the invariant, so it belongs where the
 * key is declared, and a new coordinate-keyed relation gets the rejection by construction instead
 * of by remembering to copy it. Relations whose key is wider ({@code ConditionRelation}'s
 * {@code (coordinate, table)}) keep their own check, because a coordinate maps to several of
 * their rows and this index's contract is that it maps to at most one.
 *
 * <p>The lookup is a map read rather than a scan of the rows, which is a consequence of holding
 * the map the key rejection has to build anyway and not a measured cost claim about the scan it
 * replaces. The map's iteration order is producer order, so {@link #coordinates()} and
 * {@link #byCoordinate()} both read out in the order the rows arrived and a consumer's output
 * stays deterministic.
 *
 * @param <T> the command row type.
 */
public final class CoordinateIndex<T> {

    private final List<T> rows;
    private final Map<FieldCoordinates, T> byCoordinate;

    private CoordinateIndex(List<T> rows, Map<FieldCoordinates, T> byCoordinate) {
        this.rows = rows;
        this.byCoordinate = byCoordinate;
    }

    /**
     * Indexes {@code rows} by the coordinate {@code key} reads off each, rejecting a coordinate
     * that appears twice. {@code relationName} names the relation in that rejection, so the
     * message says which producer minted the collision.
     */
    public static <T> CoordinateIndex<T> of(List<T> rows, Function<T, FieldCoordinates> key,
            String relationName) {
        var copy = List.copyOf(rows);
        var map = new LinkedHashMap<FieldCoordinates, T>();
        for (var row : copy) {
            var coordinate = Objects.requireNonNull(key.apply(row), "coordinate");
            if (map.putIfAbsent(coordinate, row) != null) {
                throw new IllegalArgumentException(
                    "the " + relationName + " relation is keyed by coordinate; " + coordinate
                    + " appeared twice");
            }
        }
        return new CoordinateIndex<>(copy, Collections.unmodifiableMap(map));
    }

    /** The rows in producer order. */
    public List<T> rows() {
        return rows;
    }

    /** The rows by coordinate, for per-coordinate reads. */
    public Map<FieldCoordinates, T> byCoordinate() {
        return byCoordinate;
    }

    /** The relation's coordinate keys, in producer order. */
    public List<FieldCoordinates> coordinates() {
        return List.copyOf(byCoordinate.keySet());
    }

    /** The row for one coordinate, empty when the relation holds none for it. */
    public Optional<T> rowFor(String parentTypeName, String fieldName) {
        return Optional.ofNullable(
            byCoordinate.get(FieldCoordinates.coordinates(parentTypeName, fieldName)));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CoordinateIndex<?> that && rows.equals(that.rows);
    }

    @Override
    public int hashCode() {
        return rows.hashCode();
    }

    @Override
    public String toString() {
        return rows.toString();
    }
}
