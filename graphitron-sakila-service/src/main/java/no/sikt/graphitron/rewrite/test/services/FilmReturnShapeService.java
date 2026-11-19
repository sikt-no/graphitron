package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmListRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.LanguageRecord;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.Row1;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Services whose answer a field is or is not handed, the mismatched ones refused as
 * {@code SERVICE_RETURN_MISMATCH}: records of another table, values of another class than the
 * field's scalar, many where the field is one, one where it is a list, at a root and per key under
 * Film. Never called. The film_list answers are records of a table with no primary key to read them
 * back by, refused as {@code KEY_TABLE_WITHOUT_PRIMARY_KEY}. Among those handed: one record into a
 * payload that holds a film, and each language's films keyed by the language's key as a row.
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

    public static FilmRecord filmForPayload() {
        return null;
    }

    public static Map<Row1<Integer>, List<FilmRecord>> filmsByLanguageRow(Set<Row1<Integer>> languageIds) {
        return Map.of();
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

    public static Map<FilmRecord, String> titleByFilm(Set<FilmRecord> films) {
        return Map.of();
    }

    public static Integer filmCount() {
        return 0;
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

    public static List<FilmListRecord> filmList() {
        return List.of();
    }

    public static Map<FilmRecord, List<FilmListRecord>> filmListByFilm(Set<FilmRecord> films) {
        return Map.of();
    }

    public static Map<FilmListRecord, Integer> rankByFilmList(Set<FilmListRecord> listings) {
        return Map.of();
    }

    /** Films as a set: a collection delivering many, as a list does. */
    public static Set<FilmRecord> filmsAsSet() {
        return Set.of();
    }

    /** Films as an Iterable, delivering many as a list does. */
    public static Iterable<FilmRecord> filmsAsIterable() {
        return List.of();
    }

    /** Each film's language as a jOOQ record of no table, which names no record class to lift. */
    public static Map<FilmRecord, Record> languageAsPlainRecord(Set<FilmRecord> films) {
        return Map.of();
    }
}
