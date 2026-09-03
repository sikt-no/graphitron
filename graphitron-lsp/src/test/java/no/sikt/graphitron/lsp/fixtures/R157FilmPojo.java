package no.sikt.graphitron.lsp.fixtures;

/**
 * Pipeline test fixture: a plain Java class with bean accessors so the
 * classifier classifies it as a backed POJO result type,
 * and so the store's member-slot rule answers {@code filmId} / {@code title}
 * for it off the accessors rather than off components.
 */
public class R157FilmPojo {
    private Integer filmId;
    private String title;

    public R157FilmPojo() {}

    public Integer getFilmId() { return filmId; }
    public void setFilmId(Integer filmId) { this.filmId = filmId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}
