package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture for what an {@code @error} type's extra field is not read from: a bare {@code code()} on a
 * class that is no record, and a public {@code hint} field. An extra field reads a get or is getter,
 * so an {@code @error} type naming either is refused as {@code ERROR_FIELD_UNREADABLE}. Never thrown.
 */
public class FilmLookupCodedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public final String hint;

    public FilmLookupCodedException(String message, String hint) {
        super(message);
        this.hint = hint;
    }

    public String code() {
        return "FILM_LOOKUP";
    }
}
