package no.sikt.graphitron.rewrite.model;

/**
 * The {@code carrier} dimension (R299): the GraphQL parent-type category a field is defined on, which
 * <em>is</em> its field type. The carrier is position, and it is also the legality gate the retired
 * {@code producer} axis used to imply: write intents only on {@link #Mutation}, {@code NodeResolve}
 * only on {@link #Query}, {@code Nesting} only on {@link #Source}. The carrier is materialised on the
 * field model by {@link OutputField#carrier()}, defaulted per carrier root ({@link QueryField} →
 * {@link #Query}, {@link MutationField} → {@link #Mutation}, {@link ChildField} → {@link #Source}), so
 * an off-carrier {@link Intent} is unrepresentable.
 *
 * <p>A flat typed enum, not a nested wrapper: the carrier carries no per-value payload, so a single
 * enum value is the whole fact. The R281/R299 corpus mirrors this value set SDL-side (the
 * {@code @classified(carrier:)} directive argument) and asserts it against {@link OutputField#carrier()}.
 *
 * <p>See {@code roadmap/datafetcher-field-dimensional-slots.md} (R290, which materialises these slots)
 * and the {@code carrier} axis in R222's "Field-side dimensional model (refined 2026-06-13)".
 */
public enum Carrier {
    /** The root {@code Query} type ({@link QueryField} leaves). */
    Query,
    /** The root {@code Mutation} type ({@link MutationField} leaves). */
    Mutation,
    /** Every other (non-Subscription) type ({@link ChildField} leaves; the renamed {@code SourceField}). */
    Source
}
