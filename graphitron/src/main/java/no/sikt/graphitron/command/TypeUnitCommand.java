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
     * empty class the retired loop emitted) and for every nesting/pivot-reached type that owns
     * at least one classified coordinate (the schema's nesting-reach fold, whose one
     * representative wiring also decides the emitted content). {@code @error} types have their
     * own arm ({@link ErrorFetchersUnit}), which carries the extra ref their bodies name. The
     * key is the bare type name; the coarse grain for shared nested types is made safe by the
     * nesting-parent compatibility validation, and its widening is recorded on the roadmap.
     */
    record FetchersUnit(String typeName, UnitRef unit) implements TypeUnitCommand {
        public FetchersUnit {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(unit, "unit");
        }
    }

    /**
     * An {@code @error} type's {@code <Type>Fetchers} class: the fixed {@code path} / {@code
     * message} method pair, emitted for every {@code ErrorType} the schema registers. Its own arm
     * rather than a {@link FetchersUnit} because its body names a second unit: {@code message}
     * resolves an authored {@code description:} by walking the type's own {@code Mapping[]}
     * constant on {@code errorMappings}, so the join between the two addresses is the producer's
     * to make. Membership and naming only, as every row: which handler contributes an override is
     * read from the model by the renderer.
     */
    record ErrorFetchersUnit(String typeName, UnitRef unit, UnitRef errorMappings)
            implements TypeUnitCommand {
        public ErrorFetchersUnit {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(errorMappings, "errorMappings");
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

    /**
     * The graphql-java form a schema-shape class builds, decided once by the producer's total
     * switch over the classification permits (a new permit is a compile-time decision there,
     * never a silent skip at a renderer's {@code instanceof} chain); the renderer dispatches on
     * this value and resolves the concrete graphql-java type for the body render-side.
     */
    enum SchemaShapeForm { OBJECT, INTERFACE, UNION, INPUT, ENUM }

    /**
     * A {@code <TypeName>Type} schema-shape class ({@code <pkg>.schema}): emitted for every
     * user-surface classified type (the federation-internal {@code _}-prefixed names, scalars
     * and unclassified verdicts have no row; the underscore skip's one home is the producer).
     * {@link #form} routes the renderer; {@link #registersFetchers} is the coordinate-grain
     * fact "this class carries a {@code registerFetchers} body", the one home the registrations
     * emitter renders from and the schema-class assembler's registration loop reads, so the
     * class, the body and the call cannot drift.
     */
    record SchemaShapeUnit(String typeName, UnitRef unit, SchemaShapeForm form,
            boolean registersFetchers) implements TypeUnitCommand {
        public SchemaShapeUnit {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(form, "form");
        }
    }
}
