package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * Methods an editor hovers: {@link #list} declares a generic return, which a hover spells as written
 * rather than as its erasure, and {@link #page} is overloaded at one and two arguments, which a
 * hover shows both of.
 */
public final class FilmCatalogService {

    private FilmCatalogService() {}

    public static List<FilmRecord> list(int limit) {
        return List.of();
    }

    public static Object page(Object film) {
        return film;
    }

    public static Object page(Object film, int limit) {
        return film;
    }
}
