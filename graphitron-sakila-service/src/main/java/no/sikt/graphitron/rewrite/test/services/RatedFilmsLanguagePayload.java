package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;

import java.util.List;

/**
 * Multi-tenant fixture: {@link RateFilmsPayload} beside a global row. {@code language} is keyed but
 * otherwise empty; graphitron re-projects it on the payload's own {@code language} coordinate,
 * which is a global read, so the request's default tenant answers it whatever tenant the service
 * ran on.
 */
public record RatedFilmsLanguagePayload(String ranOn, List<FilmRecord> films, LanguageRecord language) {
}
