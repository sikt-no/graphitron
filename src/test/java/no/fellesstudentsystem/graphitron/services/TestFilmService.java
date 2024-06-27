package no.fellesstudentsystem.graphitron.services;

import no.fellesstudentsystem.graphitron.records.TestFilmRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;
import org.jooq.DSLContext;

/**
 * Fake service for service tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class TestFilmService {
    public TestFilmService(DSLContext context) {}

    public FilmRecord editFilm(TestFilmRecord record) {
        return null;
    }
}
