package no.sikt.graphitron.rewrite.test.backing;

import java.util.List;

/** The producers the graph is grounded from, one per shape a returned class can take. */
public final class FilmService {

    private FilmService() {}

    public static List<Film> findAll() {
        return List.of();
    }

    public static Integer count() {
        return 0;
    }

    public static NodeValue node() {
        return null;
    }

    public static Left left() {
        return null;
    }

    public static Right right() {
        return null;
    }

    public static List<Carrier> one() {
        return List.of();
    }

    public static List<Film> search(FilmFilter filter) {
        return List.of();
    }

    public static GroundedDto grounded() {
        return null;
    }
}
