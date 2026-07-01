package no.sikt.graphitron.rewrite.test.services;

/**
 * R150 fixture: nested input bean inside {@link FilmReviewDetails}. Exercises the recursive
 * {@code InputBean} leaf path of the {@code create<TypeName>} helper generator.
 */
public record FilmReviewTag(String name, Integer weight) {}
