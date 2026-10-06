package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Multi-tenant fixture: a {@code @tenant}-marked {@code filmId} beside a film decoded from a
 * {@code @nodeId}, two values naming a tenant that must agree before the service runs.
 */
public record FilmIdAndFilmRating(Integer filmId, FilmRecord film) {
}
