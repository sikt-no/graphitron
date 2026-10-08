package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;

/**
 * Multi-tenant fixture: what the {@code @globalData} {@link FilmRatingService#globalLanguage}
 * returns. {@code servedBy} is the database the service ran on; {@code language} is keyed but
 * otherwise empty, and its re-projection on the payload's own coordinate is a global read.
 */
public record GlobalLanguagePayload(String servedBy, LanguageRecord language) {
}
