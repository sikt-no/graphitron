package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.UnitRef;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The condition command relation of one generation run. Keyed {@code (coordinate, resolvedTable)}
 * (participants expanded, child, nested and lookup coordinates included); every row renders glue
 * and every WHERE consumer calls it. The committed/uncommitted split that carried the render-side
 * migration dial through the root-only window retired when call-site convergence closed it: two
 * lists equal by construction would be a restatement with nothing binding them.
 */
public record ConditionRelation(List<ConditionCommand> rows) {

    public ConditionRelation {
        rows = List.copyOf(rows);
        long distinctKeys = rows.stream().map(r -> r.coordinate() + "@" + r.table().tableName()).distinct().count();
        if (distinctKeys != rows.size()) {
            throw new IllegalArgumentException(
                "the condition relation is keyed (coordinate, table); a key appeared twice");
        }
    }

    /** The distinct glue classes the rows land on: the write step's landing addresses. */
    public List<UnitRef> units() {
        var units = new LinkedHashSet<UnitRef>();
        for (var row : rows) {
            units.add(row.glue().owner());
        }
        return List.copyOf(units);
    }
}
