package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture: the payload of {@code Query.filmLookupRefused}, a defaulted {@code title} slot plus the
 * errors slot as {@code List<?>}. See {@link FilmRefusedLookupService}.
 */
public record FilmRefusedLookupPayload(String title, List<?> errors) {}
