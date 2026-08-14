package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.Language;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;
import org.jooq.DSLContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Execution fixture for the {@code @sourceRow}-declared batch key: a root {@code @service} handing
 * back scalar-only {@link LanguageSummary} rows, and the child {@code @service} that batches against
 * the key {@link LanguageSummaryLifter} produces from them.
 *
 * <p>Two of the three rows share a language, so a working DataLoader dispatch dedups three parents
 * to two distinct keys. {@link #languageNames} encodes the batch's own size into every value it
 * returns, which is what lets one assertion tell a single batched call from one call per parent
 * without any static counter: per-parent dispatch would report a batch of one.
 */
public final class LanguageSummaryService {

    private LanguageSummaryService() {}

    /** Hand-rolled parents, no DB round-trip. The ids match the {@code language} seed in init.sql. */
    public static List<LanguageSummary> languageSummaries() {
        return List.of(
            new LanguageSummary(1, "first"),
            new LanguageSummary(2, "second"),
            new LanguageSummary(1, "third")
        );
    }

    /**
     * The batched child. The keys carry {@code LANGUAGE_ID} and nothing else, so the name is
     * fetched here in one query for the whole batch; the {@code @<batch size>} suffix is what the
     * execution assertion reads the dispatch shape off.
     */
    public static Map<LanguageRecord, String> languageNames(Set<LanguageRecord> keys, DSLContext dsl) {
        List<Integer> ids = keys.stream().map(LanguageRecord::getLanguageId).toList();
        Map<Integer, String> nameById = dsl
            .selectFrom(Language.LANGUAGE)
            .where(Language.LANGUAGE.LANGUAGE_ID.in(ids))
            .fetchMap(Language.LANGUAGE.LANGUAGE_ID, Language.LANGUAGE.NAME);

        Map<LanguageRecord, String> result = new LinkedHashMap<>();
        for (LanguageRecord key : keys) {
            String name = nameById.get(key.getLanguageId());
            result.put(key, name == null ? null : name.trim() + "@" + keys.size());
        }
        return result;
    }
}
