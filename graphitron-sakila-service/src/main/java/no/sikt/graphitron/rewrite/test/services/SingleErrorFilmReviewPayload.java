package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture: the payload of a review submission whose {@code errors} field lists one {@code @error}
 * type directly, {@code [FilmReviewBadRating]}, rather than a union of it. The record carries the
 * data field only: the errors arrive on the {@code Outcome} the fetcher returns, never through
 * this class.
 */
public record SingleErrorFilmReviewPayload(Integer reviewId) {}
