package no.sikt.graphitron.rewrite.model;

/**
 * Whether the SQL name a carrier resolves to came from a user-authored {@code name:}
 * argument on the directive ({@code @table(name:)} for a {@link TableRef},
 * {@code @field(name:)} for a {@link ColumnRef}) or was inferred from the SDL identifier.
 *
 * <p>Two carrier-shaped families — {@link NameProvenance} on name-bearing carriers
 * ({@link TableRef}, {@link ColumnRef}) and {@link PathProvenance} on path-bearing
 * carriers (the {@code @reference(path:)}-consuming {@code ChildField} / {@code InputField}
 * permits) — keep the type system aligned with the classifier's actual outputs: a unified
 * {@code Provenance} would let inference rules belonging to one carrier shape be statically
 * permitted on the other, accepting values the classifier can never construct.
 *
 * <p>The {@link Inferred} permits-clause widens as new inference rules land
 * (e.g. {@code FromContainingType} for a future {@code @nodeId(typeName:)} default).
 *
 * <p>Carriers that are not resolved from a user-facing SDL field (PK columns lifted from
 * jOOQ catalog reflection, FK source columns synthesized into join paths, internal
 * ordering column refs) carry {@link Inferred.FromSdlName} as a default. The LSP projector
 * walks specific field permits and reads provenance off those, so internal columns are
 * never re-projected; the default value is observationally invisible to the only consumer
 * that reads provenance today.
 */
public sealed interface NameProvenance {

    /**
     * The user wrote {@code @table(name: "X")} / {@code @field(name: "x")} explicitly.
     * The LSP suppresses inferred-name inlay hints at this carrier's coordinate.
     */
    record Authored() implements NameProvenance {}

    /**
     * The user omitted the {@code name:} argument; the classifier inferred the SQL
     * name from another source. Sealed sub-family so future inference rules widen
     * the permits-clause rather than collapsing into an enum-like discriminator.
     */
    sealed interface Inferred extends NameProvenance permits FromSdlName {}

    /**
     * The classifier inferred the SQL name from the SDL identifier: lower-casing the
     * GraphQL type name for {@code @table}, taking the GraphQL field name verbatim for
     * {@code @field}. The single inference rule at filing for both carriers.
     */
    record FromSdlName() implements Inferred {}

    /** Singleton-style factory for the common default; allocation cost is negligible but the helper reads. */
    static NameProvenance authored() { return new Authored(); }

    /** Singleton-style factory for {@link Inferred.FromSdlName}. */
    static NameProvenance inferredFromSdlName() { return new FromSdlName(); }
}
