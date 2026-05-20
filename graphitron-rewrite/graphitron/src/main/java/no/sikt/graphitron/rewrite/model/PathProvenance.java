package no.sikt.graphitron.rewrite.model;

/**
 * Whether a {@code @reference}-consuming carrier's {@code joinPath} (or single-FK shim)
 * came from a user-authored {@code @reference(path:)} list, or was inferred from the
 * unique single-hop FK between the parent and target tables.
 *
 * <p>Sibling family to {@link NameProvenance}; split rather than unified because
 * {@code Inferred.FromUniqueFk} is meaningful only on path-bearing carriers — a unified
 * {@code Provenance} would let it be statically permitted on {@link TableRef} /
 * {@link ColumnRef}, where no inference rule can ever produce it. See {@link NameProvenance}
 * for the rationale.
 *
 * <p>Carriers: {@link no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField},
 * {@link no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField},
 * {@link no.sikt.graphitron.rewrite.model.ChildField.CompositeColumnReferenceField},
 * {@link no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField},
 * {@link no.sikt.graphitron.rewrite.model.InputField.CompositeColumnReferenceField}.
 *
 * <p>The {@link Inferred} permits-clause widens as new inference rules land
 * (e.g. multi-hop FK-chain inference if it ever ships).
 */
public sealed interface PathProvenance {

    /** The user wrote {@code @reference(path: [...])}. */
    record Authored() implements PathProvenance {}

    /**
     * The user omitted {@code path:}; the classifier inferred a single-hop FK between
     * the parent and target tables. Sealed sub-family so future inference rules widen
     * the permits-clause rather than collapsing into an enum-like discriminator.
     */
    sealed interface Inferred extends PathProvenance permits FromUniqueFk {}

    /**
     * Single-hop inference: exactly one FK connected the parent table to the target
     * table. {@code fkName} is the jOOQ catalog name of the inferred FK constraint
     * (e.g. {@code address_city_id_fk}); the LSP renders it as the inferred {@code path:}
     * value the user did not write.
     */
    record FromUniqueFk(String fkName) implements Inferred {}

    /** Helper factory for the {@link Authored} default. */
    static PathProvenance authored() { return new Authored(); }

    /** Helper factory for an inferred single-hop FK. */
    static PathProvenance fromUniqueFk(String fkName) { return new FromUniqueFk(fkName); }
}
