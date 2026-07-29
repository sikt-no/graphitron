package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.ProjectionCommand;
import no.sikt.graphitron.command.UnitRef;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The projection command relation: one row per projection unit, keyed by the unit's address
 * (type name for anchor units, {@code (anchor, typeName)} for nested units, coordinate for
 * pivot units — the key is structural in the minted {@link UnitRef}). The producer registers
 * rows through a case-folded address census, so a duplicate address never reaches this
 * constructor with two distinct rows; the check here is the relation's own integrity backstop.
 */
public record ProjectionRelation(List<ProjectionCommand> rows) {

    public ProjectionRelation {
        rows = List.copyOf(rows);
        var seen = new LinkedHashSet<String>();
        for (var row : rows) {
            if (!seen.add(row.unit().fqcn().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException(
                    "duplicate projection unit address '" + row.unit().fqcn() + "' (case-folded); "
                    + "the producer's address census must reject this before relation construction");
            }
        }
    }

    /** The distinct units this relation commits, in row order (one unit per row). */
    public List<UnitRef> units() {
        return rows.stream().map(ProjectionCommand::unit).toList();
    }
}
