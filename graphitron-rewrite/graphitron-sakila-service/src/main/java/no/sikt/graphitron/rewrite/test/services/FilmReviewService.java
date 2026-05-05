package no.sikt.graphitron.rewrite.test.services;

/**
 * R12 fixture: mutation-side {@code @error} end-to-end. The {@code submit} method drives the
 * {@code MutationServiceRecordField} emit path that broke in production (a
 * {@code @service}-backed mutation returning a {@code @record} payload with an {@code errors}
 * slot) through compile-spec and execute-spec, mirroring the query-side
 * {@link FilmLookupService} but on the mutation pillar so any future regression in
 * {@code MutationServiceRecordField}'s try/catch wrapper or {@code ErrorRouter.dispatch} arm
 * lands as a build failure rather than a production schema break.
 *
 * <p>Three branches by input:
 * <ul>
 *   <li>{@code rating} outside [1,10] — throws {@link FilmReviewBadRatingException}.</li>
 *   <li>{@code filmId == 999} — throws {@link FilmReviewMissingFilmException}.</li>
 *   <li>otherwise — happy path; returns a populated {@link FilmReviewPayload}.</li>
 * </ul>
 *
 * <p>No DB round-trip — the body is hand-rolled to keep the test deterministic and to keep the
 * scope to the schema-emit codepath we're protecting.
 */
public final class FilmReviewService {

    private FilmReviewService() {}

    public static FilmReviewPayload submit(Integer filmId, Integer rating) {
        if (filmId == null || rating == null) {
            throw new FilmReviewBadRatingException("filmId and rating are required");
        }
        if (rating < 1 || rating > 10) {
            throw new FilmReviewBadRatingException("rating must be in [1, 10]; got " + rating);
        }
        if (filmId == 999) {
            throw new FilmReviewMissingFilmException("film " + filmId + " not found");
        }
        return new FilmReviewPayload(rating * 10000 + filmId, java.util.List.of());
    }
}
