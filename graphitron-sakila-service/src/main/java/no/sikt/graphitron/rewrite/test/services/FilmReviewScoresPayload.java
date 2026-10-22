package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * The payload behind {@code FilmReviewScoresPayload}: a non-null scalar list the generated fetcher
 * reads inline off {@code Outcome.Success.value()} through the {@code scores()} accessor.
 */
public record FilmReviewScoresPayload(List<Integer> scores) {}
