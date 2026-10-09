package no.sikt.graphitron.rewrite.test.services;

/** Produces a {@link FilmShelf}, so the type a field returns it at is backed by it. */
public final class FilmShelfService {

    private FilmShelfService() {}

    public static FilmShelf shelf() {
        return new FilmShelf(new FilmBlurb(""));
    }
}
