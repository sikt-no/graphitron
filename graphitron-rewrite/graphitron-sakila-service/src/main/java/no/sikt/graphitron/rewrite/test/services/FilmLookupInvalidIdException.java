package no.sikt.graphitron.rewrite.test.services;

/**
 * R12 fixture: GENERIC-handler exception for the {@code filmLookup} query's
 * {@code FilmLookupInvalid} {@code @error} type. Sibling of
 * {@link FilmLookupNotFoundException}; the two exist together so the union's
 * TypeResolver dispatch ladder has more than one branch and the
 * {@code GraphitronSchemaClassGenerator.buildErrorTypeFieldFetchers} loop emits
 * path/message {@code DataFetcher}s for every {@code @error} type.
 */
public class FilmLookupInvalidIdException extends RuntimeException {
    public FilmLookupInvalidIdException(String message) {
        super(message);
    }
}
