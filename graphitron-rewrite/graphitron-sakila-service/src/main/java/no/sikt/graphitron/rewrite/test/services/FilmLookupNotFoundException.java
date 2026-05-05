package no.sikt.graphitron.rewrite.test.services;

/**
 * R12 fixture: GENERIC-handler exception for the {@code filmLookup} query's
 * {@code FilmLookupNotFound} {@code @error} type. Unchecked so the service signature
 * stays clean of declared-throws (which §4 would require to be covered by a matching
 * {@code @error} handler on the channel; that's already true here, but keeping it
 * unchecked keeps the fixture orthogonal to the §4 declared-exception check).
 */
public class FilmLookupNotFoundException extends RuntimeException {
    public FilmLookupNotFoundException(String message) {
        super(message);
    }
}
