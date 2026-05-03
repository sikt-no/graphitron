package no.sikt.graphitron.rewrite.generators;

import org.jooq.Record;
import org.jooq.Row1;

import java.util.List;

/**
 * Minimal service stub used by {@link FetcherPipelineTest} to verify that the
 * {@code @service} DataLoader code path can be triggered via reflection.
 *
 * <p>The {@code languageKeys} parameter uses {@code Row1<Integer>} matching the
 * {@code language_id} {@code serial} (Integer) column. Return type is the structurally-
 * required {@code List<List<Record>>} per the rows-method shape for a {@code [Film!]!} list
 * field with a {@link BatchKey.RowKeyed} (positional) batch key.
 */
public class TestFilmService {

    public static List<List<Record>> getFilms(List<Row1<Integer>> languageKeys, String filter, String tenantId) {
        throw new UnsupportedOperationException();
    }
}
