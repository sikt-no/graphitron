package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Services whose parameters do or do not take what their arguments deliver, the mismatched ones
 * refused as {@code PARAMETER_MISMATCH}: one value against a list, a list against one value, a Long
 * for an ID or an Int, a jOOQ record member for a scalar; and a source parameter collecting a class
 * of its own rather than keys, refused as {@code SERVICE_SOURCES_NOT_KEYS}. Never called.
 */
public final class ParameterShapeService {

    private ParameterShapeService() {}

    /** A class of the author's own, which no parent key is put into. */
    public record FilmKey(Integer filmId) {}

    /**
     * An input bean whose members an ID cannot fill, each refused as {@code PARAMETER_MISMATCH}: a
     * Long, which an ID does not arrive as, and a film record, which an ID decodes into only through
     * {@code @nodeId}. Nested here rather than at the top level of its package, so the capture is
     * shown to build a bean declared inside another class as it builds any other.
     */
    public record FilmIdBean(Long filmId, FilmRecord film) {}

    public static String byTitle(String title) {
        return title;
    }

    public static String byTitles(List<String> titles) {
        return String.join(",", titles);
    }

    public static String byTitleArray(String[] titles) {
        return String.join(",", titles);
    }

    public static String byLongId(Long id) {
        return String.valueOf(id);
    }

    public static String byIntId(int id) {
        return String.valueOf(id);
    }

    public static String byFilmId(Integer id) {
        return String.valueOf(id);
    }

    public static String byAnything(Object value) {
        return String.valueOf(value);
    }

    public static String withBean(FilmIdBean in) {
        return String.valueOf(in);
    }

    public static String withNumberBean(FilmNumberBean in) {
        return String.valueOf(in);
    }

    public static Map<FilmKey, String> titlesByFilmKey(Set<FilmKey> keys) {
        return Map.of();
    }
}
