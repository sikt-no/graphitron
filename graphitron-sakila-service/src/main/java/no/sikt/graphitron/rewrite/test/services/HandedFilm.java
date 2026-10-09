package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * A payload a producer hands back as a Java object, carrying a scalar and a film. A child field
 * naming a {@code @table} type under it has no enclosing statement to be projected out of, so it
 * costs a fetch of its own.
 */
public record HandedFilm(String name, FilmRecord film) {}
