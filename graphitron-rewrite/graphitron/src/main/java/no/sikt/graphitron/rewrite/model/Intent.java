package no.sikt.graphitron.rewrite.model;

/**
 * The {@code intent} dimension (R299): the operation kind a field classifies to. This is the full
 * model set R222 settled; {@link OutputField#intent()} populates only the values the current leaf set
 * permits, so some values stay <em>modeled-but-unpopulated</em> (declared gaps, exercised-or-allowlisted
 * by the R281/R299 corpus's {@code everyDimensionValueIsExercised} meta-test, never silently absent).
 *
 * <p>A flat typed enum, not a nested wrapper: cardinality (bulk, list) and targeting shape ride slots
 * beside the intent, so the value itself carries no payload. The corpus mirrors this value set
 * SDL-side (the {@code @classified(intent:)} directive argument) and asserts it against
 * {@link OutputField#intent()}.
 *
 * <p>{@link Carrier} gates which intents are legal: write intents only on {@code Mutation},
 * {@code NodeResolve} only on {@code Query}, {@code Nesting} only on {@code Source}.
 *
 * <p>See {@code roadmap/datafetcher-field-dimensional-slots.md} (R290, which materialises these slots)
 * and the {@code intent} axis in R222's "Field-side dimensional model (refined 2026-06-13)".
 */
public enum Intent {
    // read
    /** A catalog read: a query or correlated subquery projecting a table/column/record. */
    Fetch,
    /** A keyed read establishing a positional input-list / output-list correspondence ({@code @lookupKey}). */
    Lookup,
    /** Relay {@code node}/{@code nodes} resolution (cardinality is a slot, not a second intent). */
    NodeResolve,
    /** Federation {@code _entities} resolution. Modeled-but-unpopulated: no classified leaf today. */
    EntityResolve,
    /** Connection {@code totalCount}. Modeled-but-unpopulated: behind the ConnectionType quarantine. */
    Count,
    /** Connection facets. Modeled-but-unpopulated: behind the ConnectionType quarantine. */
    Facet,
    /** A structural nesting that produces nothing, inherits the parent's scope, and regroups children. */
    Nesting,

    // write
    /** A DML INSERT write. */
    Insert,
    /** A DML UPSERT write. */
    Upsert,
    /** A DML UPDATE write (PK/UK-identified). */
    Update,
    /** A condition-matched UPDATE. Modeled-but-unpopulated: unimplemented. */
    UpdateMatching,
    /** A DML DELETE write (PK/UK-identified). */
    Delete,
    /** A condition-matched DELETE. Modeled-but-unpopulated: unimplemented. */
    DeleteMatching,

    // service
    /** A developer {@code @service} read: graphitron knows only mutate-or-not about the opaque code. */
    QueryService,
    /** A developer {@code @service} write (root {@code Mutation} service carrier). */
    MutationService
}
