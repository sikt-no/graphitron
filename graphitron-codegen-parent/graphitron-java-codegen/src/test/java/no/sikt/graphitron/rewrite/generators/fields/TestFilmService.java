package no.sikt.graphitron.rewrite.generators.fields;

import org.jooq.Row;

import java.util.List;

/**
 * Minimal service stub used by {@link FieldsPipelineTest} to verify that the
 * {@code @service} DataLoader code path can be triggered via reflection.
 */
public class TestFilmService {

    public static List<Object> getFilms(List<Row> languageKeys, String filter, String tenantId) {
        throw new UnsupportedOperationException();
    }
}
