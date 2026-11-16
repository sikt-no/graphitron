package no.sikt.graphitron.rewrite.test.services;

/** A checked exception a service declares, which a GENERIC handler naming it or a supertype covers. */
public class FilmLookupCheckedException extends Exception {

    private static final long serialVersionUID = 1L;

    public FilmLookupCheckedException(String message) {
        super(message);
    }
}
