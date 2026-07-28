package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.UnitRef;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The condition command relation of one generation run, plus the plan's commitment of which rows
 * this run renders glue for. The relation is total from the first slice (participants expanded,
 * child and lookup coordinates included); {@link #committedRows} is the migration dial: it holds
 * exactly the rows whose consumers already call glue, so the renderer stays total over the rows
 * it is handed and neither it nor the shell filters anything. Today that is the root rows the
 * retired shim generator covered; the call-site convergence slice widens the committed set as it
 * converges callers, and the closing slice's enforcer is committed set == relation.
 */
public record ConditionRelation(List<ConditionCommand> rows, List<ConditionCommand> committedRows) {

    public ConditionRelation {
        rows = List.copyOf(rows);
        committedRows = List.copyOf(committedRows);
        for (var committed : committedRows) {
            if (!rows.contains(committed)) {
                throw new IllegalArgumentException(
                    "a committed condition row must be a row of the relation; the commitment is a"
                    + " restriction, never a second population");
            }
        }
        long distinctKeys = rows.stream().map(r -> r.coordinate() + "@" + r.table().tableName()).distinct().count();
        if (distinctKeys != rows.size()) {
            throw new IllegalArgumentException(
                "the condition relation is keyed (coordinate, table); a key appeared twice");
        }
    }

    /** The distinct glue classes the committed rows land on: the write step's landing addresses. */
    public List<UnitRef> committedUnits() {
        var units = new LinkedHashSet<UnitRef>();
        for (var row : committedRows) {
            units.add(row.glue().owner());
        }
        return List.copyOf(units);
    }
}
