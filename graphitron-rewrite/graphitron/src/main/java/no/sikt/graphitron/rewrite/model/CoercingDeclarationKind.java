package no.sikt.graphitron.rewrite.model;

/**
 * Why a {@link graphql.schema.Coercing}'s {@code I} type parameter resolved to
 * {@link Object} at codegen, classified by what the author would have to change to fix it.
 * Each variant maps to a different user-facing hint produced by
 * {@link ScalarResolution.Rejected.CoercingErased}.
 */
public enum CoercingDeclarationKind {
    /**
     * {@code new Coercing<Money, Money>() { ... }} — inferred-from-context generic args are
     * erased on anonymous classes. Fix: extract the Coercing to a named class.
     */
    ANONYMOUS_CLASS,
    /**
     * The Coercing is declared without type parameters at all. Fix: declare concrete type
     * parameters on the Coercing.
     */
    RAW_TYPE,
    /**
     * Named class whose type parameters resolve to {@code Object} (declared
     * {@code Coercing<Object, Object>} by mistake, or {@code extends Coercing} without
     * parameters). Fix: declare concrete type parameters at the parameter declaration site.
     */
    ERASED_NAMED_CLASS
}
