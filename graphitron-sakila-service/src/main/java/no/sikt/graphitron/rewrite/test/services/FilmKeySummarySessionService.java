package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import org.jooq.DSLContext;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Witnesses for a {@link FilmKeySummary} session handle: a root call taking the whole handle under
 * {@code $session}, and a batched child taking one of its members under {@code $session.label}.
 */
public final class FilmKeySummarySessionService {

    private FilmKeySummarySessionService() {}

    /** The handle's label, read off the whole handle. */
    public static String labelOf(DSLContext dsl, FilmKeySummary summary) {
        return summary.label();
    }

    /** Each film paired with the handle's label, read as a member. */
    public static Map<FilmRecord, String> labelledFilms(Set<FilmRecord> films, DSLContext dsl, String label) {
        return films.stream().collect(Collectors.toMap(Function.identity(), film -> label));
    }
}
