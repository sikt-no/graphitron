package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture: the backing record for the root
 * {@code @service} Outcome payload {@code FilmReviewWithFilmPayload}. It pairs a {@code @table}-bound
 * DataLoader data field ({@code film}, keyed off {@link #filmId} via
 * {@link FilmReviewWithFilmPayloadLifter}) with the errors field, a combination
 * found nowhere in sakila.
 *
 * <p>Under the {@code Outcome} wrapper transport the record's data-field accessors ({@link #reviewId},
 * {@link #filmId}) are read off {@code Outcome.Success.value()} once the generated fetchers narrow
 * the source; the errors field reads {@code Outcome.ErrorList.errors} instead of this record's
 * {@code errors} slot, which is retained only to mirror {@link FilmReviewPayload}'s canonical shape.
 */
public record FilmReviewWithFilmPayload(Integer reviewId, Integer filmId, List<?> errors) {}
