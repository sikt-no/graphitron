package no.sikt.graphitron.command;

import java.util.Objects;

/**
 * One row of the type-keyed command relation: a per-type generated unit, keyed
 * {@code (typeName, arm)}. The arms are the unit kinds the per-type generator families used to
 * decide with their own membership loops ("should I emit my kind for this type"); a row existing
 * IS the decision, made once by the producer, and the shell folds over the rows rendering each
 * unit at the address its row committed. Arms are declared with their first produced row, per
 * the migration's non-vacuity discipline; the remaining per-type families (the schema-shape
 * classes, the fetchers classes) join as their kinds cut over.
 *
 * <p>Rows carry membership and naming, never content: the renderers keep their model reads for
 * the class bodies, mirroring how the global command relation left per-family argument assembly
 * shell-side.
 */
public sealed interface TypeUnitCommand {

    /** The classified type this row's unit is generated for. */
    String typeName();

    /**
     * The input-record carrier class ({@code <pkg>.inputs.<Input>}): emitted for an SDL input
     * type exactly when it is argument-reachable (the schema's
     * {@code argumentReachableInputs} fold, a transitive closure over field arguments and
     * nested input components) and carries a record shape. Non-reachable inputs are dead schema
     * and get no row.
     */
    record InputRecordUnit(String typeName, UnitRef unit) implements TypeUnitCommand {
        public InputRecordUnit {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(unit, "unit");
        }
    }
}
