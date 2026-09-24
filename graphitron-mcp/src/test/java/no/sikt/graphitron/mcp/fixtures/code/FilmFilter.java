package no.sikt.graphitron.mcp.fixtures.code;

/** A setter-shaped input holder, reached as a parameter of {@link FilmService#search}. */
public class FilmFilter {

    private String title;
    private int releaseYear;

    /** Fills {@code title}. */
    public void setTitle(String title) {
        this.title = title;
    }

    /** Fills {@code releaseYear}; the other member, so the ordering is observable. */
    public void setReleaseYear(int releaseYear) {
        this.releaseYear = releaseYear;
    }

    /** A getter beside the setters: readable and writable are different questions about one name. */
    public String getTitle() {
        return title;
    }

    /** Not a setter, taking no argument, so it fills nothing. */
    public void reset() {
        title = null;
        releaseYear = 0;
    }
}
