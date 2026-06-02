package no.sikt.graphitron.rewrite.test.services;

/**
 * R268 fixture (errorchannel-arm-switch-table-data-fields.md): the {@code @service} behind
 * {@code Mutation.submitFilmReviewWithFilm}. Mirrors {@link FilmReviewService#submit}'s three-way
 * branching but returns a {@link FilmReviewWithFilmPayload} whose {@code film} data field is a
 * {@code @table}-bound DataLoader lookup sibling to the errors field.
 *
 * <p>The success arm returns a populated payload (reviewId + filmId); the generated fetchers wrap it
 * in {@code Outcome.Success}, the {@code film} fetcher batch-loads the {@code Film} row by
 * {@code filmId}, and the errors field resolves empty. A thrown exception routes through
 * {@code ErrorRouter} into {@code Outcome.ErrorList}; the {@code film} fetcher then arm-switches to
 * {@code completedFuture(null)} before key extraction, rendering {@code film: null} while the
 * sibling errors field stays reachable.
 *
 * <p>No DB round-trip in the service body; the {@code film} lookup is the generated DataLoader's job.
 */
public final class FilmReviewWithFilmService {

    private FilmReviewWithFilmService() {}

    public static FilmReviewWithFilmPayload submit(Integer filmId, Integer rating) {
        if (filmId == null || rating == null) {
            throw new FilmReviewBadRatingException("filmId and rating are required");
        }
        if (rating < 1 || rating > 10) {
            throw new FilmReviewBadRatingException("rating must be in [1, 10]; got " + rating);
        }
        if (filmId == 999) {
            throw new FilmReviewMissingFilmException("film " + filmId + " not found");
        }
        return new FilmReviewWithFilmPayload(rating * 10000 + filmId, filmId, java.util.List.of());
    }
}
