package no.sikt.graphitron.rewrite.generators;

import org.jooq.Row1;

import java.util.List;

/**
 * Minimal service stub used by {@link FieldsPipelineTest} to verify that the
 * {@code @service} DataLoader code path can be triggered via reflection.
 *
 * <p>The {@code languageKeys} parameter uses {@code Row1<Long>} matching the {@code language_id}
 * BIGINT column (jOOQ maps BIGINT → {@code java.lang.Long}).
 */
public class TestFilmService {

    public static List<Object> getFilms(List<Row1<Long>> languageKeys, String filter, String tenantId) {
        throw new UnsupportedOperationException();
    }
}
