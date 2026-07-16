package no.sikt.graphitron.rewrite.test.services;

/**
 * Compilation-tier fixture: the nested-payload counterpart of {@link FilmReviewService#submit}.
 * A mutation {@code @service} returning a <em>nested</em> record payload
 * ({@link NestedFilmReviewPayloadHolder.Payload}) that also carries an {@code @error} channel, so a
 * single fixture reaches both remaining in-hand {@code bestGuess} sites this pass fixes:
 * {@code TypeFetcherGenerator.computeMutationServiceRecordReturnType} (the mutation-service-record
 * fetcher return type) and {@code FieldBuilder.resolveErrorChannel} (the Outcome payload ctor arm).
 *
 * <p>Reuses {@link FilmReviewBadRatingException} / {@link FilmReviewMissingFilmException} so
 * the {@code errors} slot resolves against the existing {@code FilmReviewError} union without new
 * {@code @error} types. No DB round-trip — hand-rolled to keep the scope to the schema-emit codepath
 * the two swaps protect.
 */
public final class NestedFilmReviewService {

    private NestedFilmReviewService() {}

    /**
     * Happy path returns an empty (errors-only) nested payload; an out-of-range rating or a
     * {@code filmId == 999} throws so the Outcome error arm (which constructs
     * {@code new NestedFilmReviewPayloadHolder.Payload(errors)} via the fixed
     * {@code resolveErrorChannel} ctor arm) is exercised.
     */
    public static NestedFilmReviewPayloadHolder.Payload submit(Integer filmId, Integer rating) {
        if (filmId == null || rating == null) {
            throw new FilmReviewBadRatingException("filmId and rating are required");
        }
        if (rating < 1 || rating > 10) {
            throw new FilmReviewBadRatingException("rating must be in [1, 10]; got " + rating);
        }
        if (filmId == 999) {
            throw new FilmReviewMissingFilmException("film " + filmId + " not found");
        }
        return new NestedFilmReviewPayloadHolder.Payload(java.util.List.of());
    }
}
