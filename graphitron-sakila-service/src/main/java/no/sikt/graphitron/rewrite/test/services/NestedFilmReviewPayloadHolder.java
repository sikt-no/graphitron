package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Compilation-tier fixture: the nested-carrier photo-negative of {@link FilmReviewPayload}.
 * Where {@code FilmReviewPayload} is a top-level record (binary name has no {@code $}), this payload
 * is a <em>nested</em> record ({@code Payload} enclosed here), so its binary name is
 * {@code NestedFilmReviewPayloadHolder$Payload}.
 *
 * <p>Returned by {@link NestedFilmReviewService#submit}, so the SDL {@code NestedFilmReviewPayload}
 * type binds as a class-backed {@code ResultReturnType} whose {@code fqClassName} carries the
 * {@code $}-qualified binary name, and the field classifies as a {@code MutationServiceRecordField}
 * with an {@code errors} field resolving an {@code ErrorChannel}. It is the compiling witness that two emit sites spell the
 * nested name as the JLS-legal {@code NestedFilmReviewPayloadHolder.Payload}, not the binary
 * {@code NestedFilmReviewPayloadHolder$Payload}:
 * <ul>
 *   <li>{@code TypeFetcherGenerator.computeMutationServiceRecordReturnType}, the mutation twin of
 *   the query-side {@code computeServiceRecordReturnType}, sources the fetcher return type from
 *   {@code ServiceMethodCall.javaReturnType()}.</li>
 *   <li>{@code FieldBuilder.resolveServiceOutcomeChannel}, the {@code @service} Outcome channel
 *   resolver, which reads the payload's backing class.</li>
 * </ul>
 * A regression to {@code ClassName.bestGuess} over the binary name at either site fails the
 * {@code graphitron-sakila-example} compile gate on this fixture's generated output.
 *
 * <p>The payload carries no other field on purpose; see the {@link Payload} javadoc for why a
 * scalar data field would defeat this witness.
 */
public final class NestedFilmReviewPayloadHolder {

    private NestedFilmReviewPayloadHolder() {}

    /**
     * Nested record payload carrying <em>only</em> its {@code errors} component.
     *
     * <p>Deliberately no scalar/property data field: a scalar read off the backing record routes
     * through {@code FetcherEmitter.recordReadBinding} / {@code inlineSuccessRead}, which cast
     * via {@code ClassName.bestGuess(fqClassName)} (they hold only a binary string, no reflected
     * {@code Class<?>}) and would spell the non-compiling {@code Outer$Nested} for a nested record.
     * Errors-only keeps this fixture compiling as a witness for the two sites named on the
     * enclosing class, independent of those read paths.
     */
    public record Payload(List<?> errors) {}
}
