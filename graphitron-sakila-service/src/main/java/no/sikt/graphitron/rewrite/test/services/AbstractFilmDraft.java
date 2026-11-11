package no.sikt.graphitron.rewrite.test.services;

/**
 * A draft nothing can construct, being abstract, for the case where an input object is built into a
 * class with no constructor to call: {@link FilmDraftService#countAbstract} takes it.
 */
public abstract class AbstractFilmDraft {

    private String title;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
