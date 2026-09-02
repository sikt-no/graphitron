package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.CarrierDsl;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.UnitMethodRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The launcher command relation of one generation run: one row per migrated root SELECT
 * coordinate, keyed by the coordinate alone. "Exactly one launcher per covered coordinate" is
 * the relation's key rather than a property a test hunts for; a producer minting two rows for
 * one coordinate fails at construction, in the {@link CoordinateIndex} every coordinate-keyed
 * relation holds. The key is the coordinate because the launch is the dispatch
 * target a coordinate's operation member set renders into (one query unit hosting select,
 * condition, orderBy and paginate members composed into one SELECT; the reentry rows host the
 * member set's keyed re-select), so a member is never a second key column here. The write
 * member is deliberately unmaterialized in this relation: the write stays with the mutation
 * entry point, and only its reentry companion launches through these rows.
 *
 * <p>The covered family widens slice by slice as {@code LauncherCommands}' migration dial
 * shrinks; the derived-fact-equals-key-set membership enforcer lands with the closing slice,
 * when the dial empties.
 *
 * <p>{@link #carrierDsl} is the run-grain carrier-routing fact (see {@link CarrierDsl}),
 * carried on the family view that renders carriers rather than copied onto every row; it moves
 * up to the plan if a second family ever reads it.
 *
 * <p><b>Method-name census.</b> Coordinate uniqueness does not imply method-name uniqueness:
 * the {@code rows} / {@code load} / {@code lookup} formulas upper-camel a field name, which is
 * not injective, and several naming schemes mint onto one fetchers class. This relation
 * therefore also passes a {@code (owner, method)} census, keyed on the minted
 * {@link UnitMethodRef} value itself, failing construction when two rows mint one emitted
 * method (the invariant the retired method-command registry's commit throw used to carry). The
 * validator's launcher-method census is the authored-schema mirror, so an authored collision
 * fails validation with a located error before production runs.
 *
 * <p>The comparison is exact, and complete for being exact: both sides of the key are names this
 * generator minted onto one owning class, so two rows that mint one method carry the identical
 * ref. A case fold belongs only where two namespaces meet, which a method name never does (it is
 * never a file name; {@link no.sikt.graphitron.model.diagnostics.Rejection.InvalidSchema.CaseFoldCollision}
 * carries the filesystem rationale for the population that does). Folding here would reject pairs
 * whose emitted methods are distinct and legal, which is by definition not a collision. The owner
 * half needs no fold of its own either: {@code GraphitronSchemaBuilder}'s type-name-stem census
 * already rejects two case-equivalent stems upstream, so two fetchers owners cannot differ only in
 * case by the time a producer runs.
 */
public record LauncherRelation(CoordinateIndex<LauncherCommand> index, CarrierDsl carrierDsl) {

    /** The relation over {@code rows}, indexed on the coordinate key it is declared to have. */
    public LauncherRelation(List<LauncherCommand> rows, CarrierDsl carrierDsl) {
        this(CoordinateIndex.of(rows, LauncherCommand::coordinate, "launcher"), carrierDsl);
    }

    public LauncherRelation {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(carrierDsl, "carrierDsl");
        var byMethod = new LinkedHashMap<UnitMethodRef, LauncherCommand>();
        for (var row : index.rows()) {
            var existing = byMethod.putIfAbsent(row.unit(), row);
            if (existing != null) {
                throw new IllegalArgumentException(
                    "launcher method '" + row.unit().owner().fqcn() + "#"
                    + row.unit().methodName() + "' minted twice: coordinates "
                    + existing.coordinate() + " and " + row.coordinate()
                    + "; every emitted method is exactly one row's output, and the validator's"
                    + " launcher-method census must reject this before production");
            }
        }
    }

    /** The rows in producer order. */
    public List<LauncherCommand> rows() {
        return index.rows();
    }

    /** The rows by coordinate, for per-coordinate reads. */
    public Map<FieldCoordinates, LauncherCommand> byCoordinate() {
        return index.byCoordinate();
    }

    /**
     * The row for one coordinate, empty when the coordinate has not migrated onto the seam. The
     * fetcher generator dispatches on this presence rather than restating the producer's
     * membership predicate: a present row gets the launcher emission, an absent one falls
     * through to its legacy builder, and the two ends cannot drift because only one of them
     * decides.
     */
    public Optional<LauncherCommand> rowFor(String parentTypeName, String fieldName) {
        return index.rowFor(parentTypeName, fieldName);
    }
}
