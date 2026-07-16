package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;

import java.util.Map;
import java.util.Optional;

/**
 * A pure, typename-keyed fixed-point index over the schema's {@code @error} types
 * ({@link ErrorType}). Built once by {@link TypeBuilder#buildClassificationIndices} by
 * directive-scanning every SDL type (a superset of the reachable set, unpruned) through the same
 * producer classification uses ({@code buildErrorType}); read by field classification in place of a
 * keyed {@code ctx.types} lookup at the error-channel union-member scan, so the field pass carries
 * no dependency on a populated type registry for the error-member fact.
 *
 * <p>The index is pure: it carries no classification duty (no demotion, no reachability prune). The
 * superset needs no reachability pruning because an {@code @error} member is queried only by a field
 * that reaches it, so the index and the pruned registry agree on the consulted domain.
 */
record ErrorIndex(Map<String, ErrorType> byName) {

    static final ErrorIndex EMPTY = new ErrorIndex(Map.of());

    ErrorIndex {
        byName = Map.copyOf(byName);
    }

    /** The {@link ErrorType} with this GraphQL type name, if it classified as one. */
    Optional<ErrorType> forName(String typeName) {
        return Optional.ofNullable(byName.get(typeName));
    }
}
