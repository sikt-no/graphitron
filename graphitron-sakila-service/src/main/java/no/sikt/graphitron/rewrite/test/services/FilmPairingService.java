package no.sikt.graphitron.rewrite.test.services;

/**
 * A service taking two input objects of different classes and a scalar, so each argument is fed by
 * the parameter at its own position and the scalar by none: a {@code String} is a class, but not
 * one that answers a type's fields.
 */
public final class FilmPairingService {

    private FilmPairingService() {}

    public static String pair(FirstPick first, SecondPick second, String note) {
        return first.filmId() + ":" + second.filmId() + ":" + note;
    }
}
