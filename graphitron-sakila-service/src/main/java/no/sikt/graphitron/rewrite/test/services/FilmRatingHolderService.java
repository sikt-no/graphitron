package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;

import java.util.Map;
import java.util.Set;

/**
 * Multi-tenant fixture: a child service reaching its connection through its constructor rather
 * than a method parameter, so the generated call is {@code new FilmRatingHolderService(dsl)}.
 * Reports the database it was constructed on.
 */
public final class FilmRatingHolderService {

    private final DSLContext dsl;

    public FilmRatingHolderService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Map<FilmRecord, String> servedBy(Set<FilmRecord> films) {
        return FilmRatingService.byFilm(films, FilmRatingService.database(dsl));
    }
}
