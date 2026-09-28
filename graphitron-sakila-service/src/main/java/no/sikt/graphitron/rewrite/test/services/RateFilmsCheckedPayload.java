package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * Multi-tenant fixture: {@link RateFilmsPayload} on the error channel. {@code errors} is the slot
 * the generated error router fills on a mapped failure; a successful call leaves it empty.
 */
public record RateFilmsCheckedPayload(String ranOn, List<FilmRecord> films, List<?> errors) {
}
