package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture: nested input bean inside {@link FilmReviewDetails}. Exercises the recursive
 * {@code InputBean} leaf path of the {@code create<TypeName>} helper generator.
 */
public record FilmReviewTag(String name, Integer weight) {}
