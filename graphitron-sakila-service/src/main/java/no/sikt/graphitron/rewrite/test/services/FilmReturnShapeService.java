package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.LanguageRecord;
import org.jooq.Result;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Services whose answer a field is or is not handed, the mismatched ones refused as
 * {@code SERVICE_RETURN_MISMATCH}: records of another table, many where the field is one, one where
 * it is a list, at a root and per key under Film. Never called.
 */
public final class FilmReturnShapeService {

    private FilmReturnShapeService() {}

    public static List<FilmRecord> films() {
        return List.of();
    }

    public static Result<LanguageRecord> languagesAsFilms() {
        return null;
    }

    public static List<FilmRecord> filmsAsOne() {
        return List.of();
    }

    public static FilmRecord oneAsFilms() {
        return null;
    }

    public static Map<FilmRecord, List<LanguageRecord>> languagesByFilm(Set<FilmRecord> films) {
        return Map.of();
    }

    public static List<List<LanguageRecord>> languagesInOrder(List<FilmRecord> films) {
        return List.of();
    }

    public static Map<FilmRecord, Integer> lengthByFilm(Set<FilmRecord> films) {
        return Map.of();
    }

    public static Map<FilmRecord, FilmRecord> filmAsLanguage(Set<FilmRecord> films) {
        return Map.of();
    }

    public static List<LanguageRecord> oneLanguageInOrder(List<FilmRecord> films) {
        return List.of();
    }

    public static Map<FilmRecord, List<LanguageRecord>> languagesAsOne(Set<FilmRecord> films) {
        return Map.of();
    }
}
