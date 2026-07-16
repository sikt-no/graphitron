package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture: consumer-authored input bean for {@code submitFilmReviewWithDetails}. Mirrors the
 * SDL {@code input FilmReviewDetailsInput} one-to-one — scalar fields plus a nested list of
 * {@link FilmReviewTag} beans — so the generator's
 * {@code CallSiteExtraction.InputBean} arm exercises the
 * record-target path, an enum leaf, and a recursive bean leaf in a single fetcher.
 */
public record FilmReviewDetails(
    Integer filmId,
    Integer rating,
    String comment,
    List<FilmReviewTag> tags
) {}
