package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * Multi-tenant fixture: what {@link FilmRatingService} returns, a non-table wrapper. {@code ranOn}
 * is the database the service's {@code DSLContext} was connected to, so a test can see which
 * tenant served the call; {@code films} are that tenant's rows, under which tenant-scoped fields
 * inherit the tenant.
 */
public record RateFilmsPayload(String ranOn, List<FilmRecord> films) {
}
