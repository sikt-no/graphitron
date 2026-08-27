package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.UnitRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The condition command relation of one generation run. Keyed {@code (coordinate, resolvedTable)}
 * (participants expanded, child, nested and lookup coordinates included); every row renders glue
 * and every WHERE consumer calls it. The committed/uncommitted split that carried the render-side
 * migration dial through the root-only window retired when call-site convergence closed it: two
 * lists equal by construction would be a restatement with nothing binding them.
 *
 * <p>The key is wider than a coordinate, so a coordinate maps to a <em>list</em> of rows and this
 * relation holds its own index rather than {@link CoordinateIndex}, whose contract is that a
 * coordinate maps to at most one row. The index is built where the duplicate-key rejection already
 * walks the rows, so it costs the walk that was happening anyway; what it replaces is three
 * consumers each scanning the whole relation once per coordinate they ask about.
 *
 * <p>Two reads, because consumers want two different things and the difference is load-bearing.
 * {@link #rowsFor} is the whole key group, which is what a consumer folding every participant's
 * glue wants. {@link #soleRowFor} is the group asserted to hold at most one row, which is what a
 * consumer reading <em>the</em> row of a coordinate wants; it fails rather than picking, because
 * picking one of several participant rows would emit a call to an arbitrary participant's glue.
 */
public final class ConditionRelation {

    private final List<ConditionCommand> rows;
    private final Map<FieldCoordinates, List<ConditionCommand>> byCoordinate;

    public ConditionRelation(List<ConditionCommand> rows) {
        this.rows = List.copyOf(rows);
        var index = new LinkedHashMap<FieldCoordinates, List<ConditionCommand>>();
        var keys = new LinkedHashSet<String>();
        for (var row : this.rows) {
            if (!keys.add(row.coordinate() + "@" + row.table().tableName())) {
                throw new IllegalArgumentException(
                    "the condition relation is keyed (coordinate, table); a key appeared twice");
            }
            index.computeIfAbsent(row.coordinate(), ignored -> new ArrayList<>()).add(row);
        }
        index.replaceAll((coordinate, group) -> List.copyOf(group));
        this.byCoordinate = Collections.unmodifiableMap(index);
    }

    /** The rows in producer order. */
    public List<ConditionCommand> rows() {
        return rows;
    }

    /**
     * Every row at one coordinate, in producer order; empty when the coordinate has none. A
     * polymorphic coordinate reads out one row per table-bound participant, which is the shape a
     * consumer gathering glue targets folds over.
     */
    public List<ConditionCommand> rowsFor(String parentTypeName, String fieldName) {
        return byCoordinate.getOrDefault(
            FieldCoordinates.coordinates(parentTypeName, fieldName), List.of());
    }

    /**
     * The row at one coordinate when the coordinate has at most one, empty when it has none.
     *
     * <p>Fails when the coordinate has several. That is the point of the method rather than a
     * defensive check: the consumers reading a single row are the ones emitting one call to one
     * glue method, and a coordinate carrying participant rows has one glue method per participant.
     * Taking the first would emit a call to whichever participant the producer happened to mint
     * first, so the arity is asserted where it is relied on instead of being a property of the
     * caller's coordinate that nothing states.
     */
    public Optional<ConditionCommand> soleRowFor(String parentTypeName, String fieldName) {
        var group = rowsFor(parentTypeName, fieldName);
        if (group.size() > 1) {
            throw new IllegalStateException(
                "coordinate '" + parentTypeName + "." + fieldName + "' carries " + group.size()
                + " condition rows (one per participant table), and this read wants the one row of"
                + " a coordinate; a consumer emitting a single glue call cannot serve a"
                + " participant-expanded coordinate");
        }
        return group.isEmpty() ? Optional.empty() : Optional.of(group.getFirst());
    }

    /** The distinct glue classes the rows land on: the write step's landing addresses. */
    public List<UnitRef> units() {
        var units = new LinkedHashSet<UnitRef>();
        for (var row : rows) {
            units.add(row.glue().owner());
        }
        return List.copyOf(units);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConditionRelation that && rows.equals(that.rows);
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
