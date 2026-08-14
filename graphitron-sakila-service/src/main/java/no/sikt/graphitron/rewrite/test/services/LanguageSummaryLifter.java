package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;

/**
 * Fixture: the {@code @sourceRow}-declared batch-key producer for {@link LanguageSummary}'s child
 * {@code @service}. The static twin of the accessor route, so what it returns is the {@code Sources}
 * element record itself rather than the {@code RowN} tuple the {@code @table}-child lifters
 * ({@link CreateFilmPayloadLifter}) return.
 *
 * <p>Populating the key column is the author's job and is unenforced at build time, the same as on
 * the accessor route; the framework copies the key columns off whatever record comes back and hands
 * the service a fresh record carrying those and nothing else.
 */
public final class LanguageSummaryLifter {

    private LanguageSummaryLifter() {}

    /** Builds the {@code language} key record from the parent's scalar key column. */
    public static LanguageRecord key(LanguageSummary parent) {
        var record = new LanguageRecord();
        record.setLanguageId(parent.languageId());
        return record;
    }
}
