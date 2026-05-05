package no.sikt.graphitron.rewrite.test.services;

/**
 * R12 fixture (mutation-side counterpart of {@link FilmLookupNotFoundException}): GENERIC-handler
 * exception for the {@code submitFilmReview} mutation's {@code FilmReviewBadRating} {@code @error}
 * type. Mirrors the production bug shape the original {@code BehandleSakError} caught: a
 * {@code @service}-backed mutation returning a {@code @record} payload with an {@code errors}
 * union slot.
 */
public class FilmReviewBadRatingException extends RuntimeException {
    public FilmReviewBadRatingException(String message) {
        super(message);
    }
}
