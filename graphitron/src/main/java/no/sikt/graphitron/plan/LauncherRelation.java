package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.CarrierDsl;
import no.sikt.graphitron.command.LauncherCommand;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The launcher command relation of one generation run: one row per migrated root SELECT
 * coordinate, keyed by the coordinate alone. "Exactly one launcher per covered coordinate" is
 * the relation's key rather than a property a test hunts for; a producer minting two rows for
 * one coordinate fails here. The programme names the coordinate-keyed relation's key as
 * {@code (coordinate, operation)}; this family is single-operation (every row launches the query
 * operation's SELECT), so the operation column is deliberately absent and arrives with the
 * general launcher when a second operation kind needs it.
 *
 * <p>The covered family widens slice by slice as {@code LauncherCommands}' migration dial
 * shrinks; the derived-fact-equals-key-set membership enforcer lands with the closing slice,
 * when the dial empties.
 *
 * <p>{@link #carrierDsl} is the run-grain carrier-routing fact (see {@link CarrierDsl}),
 * carried on the family view that renders carriers rather than copied onto every row; it moves
 * up to the plan if a second family ever reads it.
 */
public record LauncherRelation(List<LauncherCommand> rows, CarrierDsl carrierDsl) {

    public LauncherRelation {
        rows = List.copyOf(rows);
        Objects.requireNonNull(carrierDsl, "carrierDsl");
        long distinctKeys = rows.stream().map(LauncherCommand::coordinate).distinct().count();
        if (distinctKeys != rows.size()) {
            throw new IllegalArgumentException(
                "the launcher relation is keyed by coordinate; a coordinate appeared twice");
        }
    }

    /** The rows by coordinate, for per-coordinate reads. */
    public Map<FieldCoordinates, LauncherCommand> byCoordinate() {
        var map = new LinkedHashMap<FieldCoordinates, LauncherCommand>();
        for (var row : rows) {
            map.put(row.coordinate(), row);
        }
        return map;
    }

    /**
     * The row for one coordinate, empty when the coordinate has not migrated onto the seam. The
     * fetcher generator dispatches on this presence rather than restating the producer's
     * membership predicate: a present row gets the launcher emission, an absent one falls
     * through to its legacy builder, and the two ends cannot drift because only one of them
     * decides.
     */
    public Optional<LauncherCommand> rowFor(String parentTypeName, String fieldName) {
        var coordinate = FieldCoordinates.coordinates(parentTypeName, fieldName);
        return rows.stream().filter(r -> r.coordinate().equals(coordinate)).findFirst();
    }
}
