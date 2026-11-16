package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;

import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;

/**
 * Fixture behind {@code Query.filmRecordBundle}: a {@code @service} whose bean argument holds a list
 * of {@link FilmRecord}s, each built from its input object on the column axis. It reports each
 * record's title and whether the request wrote it, so the execution tier sees the columns land.
 */
public final class FilmRecordBundleService {

    private FilmRecordBundleService() {}

    public static String describe(FilmRecordBundle bundle) {
        return bundle.name() + ":" + bundle.films().stream()
            .map(film -> film.getTitle() + (film.touched(FILM.TITLE) ? "" : " (untouched)"))
            .collect(Collectors.joining(","));
    }
}
