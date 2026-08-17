package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Compilation-tier fixture for the shared class-backed value type: one Java class
 * ({@link SharedTranslations}) produced by a batched child {@code @service} on a {@code @table}
 * parent and read as a component of a record-backed parent. Both producers put the same object at
 * {@code env.getSource()}, so the value type's child fetchers are generated once and serve both;
 * this module's release-17 compile of the emitted sources is what proves they are well-typed.
 *
 * <p>The shape is what a federated consumer subgraph is pushed toward: another subgraph declares
 * the value type's field {@code @shareable}, so its shape is fixed and cannot be renamed apart.
 * It used to be unauthorable, because the two producers reported placeholder source types that
 * could never compare equal.
 */
public final class SharedValueTypeService {

    private SharedValueTypeService() {}

    /** The batched child producer, keyed on the parent film record. */
    public static Map<FilmRecord, SharedTranslations> translationsByFilm(Set<FilmRecord> keys) {
        Map<FilmRecord, SharedTranslations> out = new LinkedHashMap<>();
        for (FilmRecord key : keys) {
            out.put(key, new SharedTranslations("tittel", key.getTitle()));
        }
        return out;
    }

    /** The producer of the reading half. */
    public static TranslationSummary translationSummary() {
        return new TranslationSummary(new SharedTranslations("tittel", "title"), "summary");
    }
}
