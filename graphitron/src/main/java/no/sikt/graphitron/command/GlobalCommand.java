package no.sikt.graphitron.command;

import java.util.List;

/**
 * One row of the global command relation: emit the units of one global family. The row names
 * every unit it commits, and the write step lands each rendered unit at its committed
 * {@link UnitRef}, so a renderer emitting an undeclared unit, or dropping a declared one, is a
 * pipeline failure rather than silent drift.
 */
public record GlobalCommand(GlobalUnitKind kind, List<UnitRef> units) {

    public GlobalCommand {
        if (kind == null) {
            throw new IllegalArgumentException("a global command requires a unit kind");
        }
        if (units == null || units.isEmpty()) {
            throw new IllegalArgumentException(
                "a global command commits at least one unit; a family that emits nothing has no row");
        }
        units = List.copyOf(units);
        long distinctNames = units.stream().map(UnitRef::simpleName).distinct().count();
        if (distinctNames != units.size()) {
            throw new IllegalArgumentException(
                "a global command's units carry distinct simple names; the write step addresses them by name");
        }
    }
}
