package no.sikt.graphitron.lsp.fixtures;

/**
 * R157 pipeline test fixture: a plain Java class with bean accessors so the
 * classifier produces
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.PojoResultType.Backed}
 * and the projector picks up {@code getFilmId} / {@code getTitle} as
 * {@link no.sikt.graphitron.rewrite.catalog.TypeBackingShape.MemberSlot}s.
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
