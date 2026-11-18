package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;

import java.util.List;

/**
 * Model-tier fixture: services whose results name no class once their containers are peeled, a type
 * variable, a raw list and an array, which a field bound to a table cannot be answered with.
 */
public final class UnclassedResultService {

    private UnclassedResultService() {}

    /** The type variable's class is the caller's to choose, so the result names none. */
    public static <T> List<T> anyFilms() {
        return List.of();
    }

    /** A raw list delivers no element class. */
    @SuppressWarnings("rawtypes")
    public static List rawFilms() {
        return List.of();
    }

    /** An array is no container the census peels, and no class backs it. */
    public static FilmRecord[] filmArray() {
        return new FilmRecord[0];
    }
}
