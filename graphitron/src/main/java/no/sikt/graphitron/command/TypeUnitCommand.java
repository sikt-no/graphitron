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

    /**
     * A {@code <Type>Fetchers} class: emitted for the fetcher-hosting classifications (table,
     * node, root and producer-result types, unconditionally, including the coordinate-less
     * empty class the retired loop emitted; {@code @error} types with their fixed method pair)
     * and for every nesting/pivot-reached type that owns at least one classified coordinate
     * (the schema's nesting-reach fold, whose one representative wiring also decides the
     * emitted content). The key is the bare type name; the coarse grain for shared nested
     * types is made safe by the nesting-parent compatibility validation, and its widening is
     * recorded on the roadmap.
     */
    record FetchersUnit(String typeName, UnitRef unit) implements TypeUnitCommand {
        public FetchersUnit {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(unit, "unit");
        }
    }

    /**
     * A connection carrier's fetchers pair: the {@code <Conn>Fetchers} lazy-resolver class and
     * the {@code <Edge>Fetchers} class, one row per {@code ConnectionType} with the two refs in
     * named roles (the one row-to-unit fan-out in the relation that is not 1:1; a list would
     * erase which ref is which).
     */
    record ConnectionFetchersUnit(String typeName, UnitRef connection, UnitRef edge)
            implements TypeUnitCommand {
        public ConnectionFetchersUnit {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(connection, "connection");
            Objects.requireNonNull(edge, "edge");
        }
    }
}
