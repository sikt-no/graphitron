package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import org.jooq.DSLContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;

/**
 * Fixture: a child {@code @service} returning an outcome, for the tests of generated code in
 * {@code graphitron}. Batched, like every child service, and reads each film's title from the
 * database it is handed, so a test of the generated fetcher runs real SQL through it.
 *
 * <p>What it does is chosen by film id, so a test picks a branch by the parent row it hands in:
 * film {@value #REFUSED} throws {@link VerdictRefusedException}, which the fixture schema maps to
 * an {@code @error} type; film {@value #BROKEN} throws an {@link IllegalStateException}, which
 * nothing maps; any other film gets a verdict built from its title.
 */
public final class FilmVerdictService {

    public static final int REFUSED = 2;
    public static final int BROKEN = 3;

    private FilmVerdictService() {}

    public static Map<FilmRecord, FilmVerdict> verdict(Set<FilmRecord> films, DSLContext dsl) {
        var verdicts = new HashMap<FilmRecord, FilmVerdict>();
        for (var film : films) {
            int id = film.getFilmId();
            if (id == REFUSED) {
                throw new VerdictRefusedException("No verdict for film " + id);
            }
            if (id == BROKEN) {
                throw new IllegalStateException("The verdict store is unreachable");
            }
            var title = dsl.select(FILM.TITLE).from(FILM).where(FILM.FILM_ID.eq(id)).fetchOne(FILM.TITLE);
            verdicts.put(film, new FilmVerdict("Seen: " + title));
        }
        return verdicts;
    }
}
