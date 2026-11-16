package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Services whose parameters do or do not take what their arguments deliver, the mismatched ones
 * refused as {@code PARAMETER_MISMATCH}: one value against a list, a list against one value, a Long
 * for an ID or an Int, a jOOQ record member for a scalar. Never called.
 */
public final class ParameterShapeService {

    private ParameterShapeService() {}

    public static String byTitle(String title) {
        return title;
    }

    public static String byTitles(List<String> titles) {
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
}
