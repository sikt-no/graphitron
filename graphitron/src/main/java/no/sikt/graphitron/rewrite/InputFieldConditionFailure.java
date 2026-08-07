package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.model.Rejection;

/**
 * One {@code @condition} build failure on an input field, accumulated by
 * {@link BuildContext#buildInputFieldCondition} in place of the prose out-param it replaces.
 *
 * <p>Not an {@link InputFieldResolution.Unresolved}: the field still classifies as its structural
 * variant, with an empty {@code condition} slot. It carries the same facts, because it is the same
 * shape of fact and its consumers (the accumulating folds in {@link InputFieldResolver} and
 * {@link TypeBuilder}) treat it alongside the resolution failures.
 *
 * <p>{@code parentTypeName} is the input type that <em>declares</em> the field, which is not the fold
 * level's type: one accumulator is threaded through the whole nesting recursion, so a nested field's
 * failure reaches the outermost fold. Carrying the declaring type is what keeps the minted coordinate
 * a real schema coordinate, and what keeps one fact minted from two consumers a single value that
 * dedup collapses.
 */
record InputFieldConditionFailure(
    String parentTypeName,
    String fieldName,
    SourceLocation location,
    Rejection rejection
) {
    /**
     * The failure rendered with the coordinate context its {@code rejection} does not carry. The
     * context lives here rather than prefixed onto the rejection because the reflect arms this
     * accumulator most often holds are typed sub-seals whose
     * {@link Rejection#prefixedWith(String)} is a deliberate no-op.
     */
    String message() {
        return "input field '" + fieldName + "' @condition: " + rejection.message();
    }
}
