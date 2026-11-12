package lib;

import no.sikt.graphitron.rewrite.test.services.FilmBlurbHolder;

/** A service for both: it produces the library's holder and takes the library's input. */
public final class HolderService {

    private HolderService() {}

    public static FilmBlurbHolder byId(Integer filmId) {
        return new FilmBlurbHolder(new Blurb(""));
    }

    public static String assign(Assignment in) {
        return in.note();
    }
}
