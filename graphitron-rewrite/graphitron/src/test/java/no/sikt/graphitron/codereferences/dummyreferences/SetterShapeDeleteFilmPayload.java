package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * Test fixture for the mutable-bean DML payload shape, sibling of {@link DeleteFilmPayload}:
 * same SDL field set ({@code film}, {@code errors}) but no all-fields constructor. The DML
 * row-slot classifier finds {@link #setFilm(FilmRecord)} as the unique setter typed as the
 * jOOQ {@link FilmRecord}; the error-channel resolver finds {@link #setErrors(List)} as the
 * errors-shaped setter. The emitter constructs the payload via no-arg ctor and per-SDL-field
 * setter invocations on the success arm; the catch-arm payload-factory mirrors the shape.
 */
public final class SetterShapeDeleteFilmPayload {

    private FilmRecord film;
    private List<Object> errors;

    public SetterShapeDeleteFilmPayload() {}

    public void setFilm(FilmRecord film) {
        this.film = film;
    }

    public void setErrors(List<Object> errors) {
        this.errors = errors;
    }

    public FilmRecord getFilm() {
        return film;
    }

    public List<Object> getErrors() {
        return errors;
    }
}
