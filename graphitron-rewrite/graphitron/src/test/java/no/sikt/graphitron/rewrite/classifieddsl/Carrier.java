package no.sikt.graphitron.rewrite.classifieddsl;

/**
 * The {@code carrier} dimension (R299): the GraphQL parent-type category a field is defined on, which
 * <em>is</em> its field type. The carrier is position, and it is also the legality gate the retired
 * {@code producer} axis used to imply: write intents only on {@link #Mutation}, {@code NodeResolve} only
 * on {@link #Query}, {@code Nesting} only on {@link #Source}. {@link LeafTupleAdapter} cannot emit an
 * off-carrier {@link Intent}.
 *
 * <p>A flat typed enum, not a nested wrapper: the carrier carries no per-value payload, so a single
 * enum value is the whole fact. {@link ClassifiedDsl#PRELUDE} mirrors this value set SDL-side, checked
 * against {@code Carrier.values()} by {@code ClassifiedDslTest#carrierMirrorsAdapterValues()}.
 *
 * <p>See {@code roadmap/intention-classification-dimension.md} and the {@code carrier} axis in R222's
 * "Field-side dimensional model (refined 2026-06-13)".
 */
public enum Carrier {
    /** The root {@code Query} type ({@code QueryField} leaves). */
    Query,
    /** The root {@code Mutation} type ({@code MutationField} leaves). */
    Mutation,
    /** Every other (non-Subscription) type ({@code ChildField} leaves; the renamed {@code SourceField}). */
    Source
}
