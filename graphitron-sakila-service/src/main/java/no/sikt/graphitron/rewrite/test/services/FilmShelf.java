package no.sikt.graphitron.rewrite.test.services;

/**
 * A bean whose getters are told apart by their parameters, returned by {@link FilmShelfService}.
 * {@code getByKey()} takes nothing and is a member an author can name. {@code getLookup(String)}
 * takes a key and is no member at all, whatever a field of that name declares as arguments. And
 * {@code getTitle} is both: the no-argument half is the member, and the keyed twin beside it is
 * not, so what the member delivers is the twin's neighbour's return and never the twin's own.
 */
public final class FilmShelf {

    private final FilmBlurb blurb;

    public FilmShelf(FilmBlurb blurb) {
        this.blurb = blurb;
    }

    public FilmBlurb getByKey() {
        return blurb;
    }

    public FilmBlurb getLookup(String key) {
        return blurb;
    }

    public String getTitle() {
        return blurb.description();
    }

    public FilmBlurb getTitle(String locale) {
        return blurb;
    }
}
