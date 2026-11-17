package no.sikt.graphitron.rewrite.test.services;

/**
 * A class that is no record, read off by a {@code @service}'s result: {@code filmId} through its
 * getter, which is read, and {@code title} through a bare accessor and {@code note} through a public
 * field, which are not. A field naming either is refused as {@code MEMBER_UNRESOLVED}. Never called.
 */
public final class FilmTitleView {

    public String note;

    public Integer getFilmId() {
        return 1;
    }

    public String title() {
        return "";
    }
}
