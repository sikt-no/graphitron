package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.Row1;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal service stub used by {@link FetcherPipelineTest} to verify that the
 * {@code @service} DataLoader code path can be triggered via reflection.
 *
 * <p>The {@code languageKeys} parameter uses {@code Row1<Integer>} matching the
 * {@code language_id} {@code serial} (Integer) column. Return type is the structurally-
 * required {@code List<List<FilmRecord>>} per the rows-method shape for a {@code [Film!]!}
 * list field with a positional {@code Row}-shaped batch key (post-R177: V = {@code
 * tb.table().recordClass()}, here {@code FilmRecord}).
 */
public class TestFilmService {

    public static List<List<FilmRecord>> getFilms(List<Row1<Integer>> languageKeys, String filter, String tenantId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Mapped-container sibling of {@link #getFilms}: {@code Set} keys + {@code Map} return
     * classify the field's loader as {@code MAPPED_SET}. Used by the R285 pipeline test to
     * assert that a mapped {@code ServiceTableField} routes through the lift (rows method returns
     * {@code Map<Row1<Integer>, List<Record>>} — the projected Record, not the developer-returned
     * {@code FilmRecord}).
     */
    public static Map<Row1<Integer>, List<FilmRecord>> getFilmsMapped(Set<Row1<Integer>> languageKeys) {
        throw new UnsupportedOperationException();
    }
}
