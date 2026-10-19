package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture behind {@code Query.filmLookupRefused}. A negative id throws
 * {@link FilmLookupRefusedException}, a {@code Throwable} that is also a {@code GraphQLError};
 * any other id returns a populated payload.
 */
public final class FilmRefusedLookupService {

    private FilmRefusedLookupService() {}

    public static FilmRefusedLookupPayload lookup(Integer id) {
        if (id != null && id < 0) {
            throw new FilmLookupRefusedException("lookup refused for film " + id);
        }
        return new FilmRefusedLookupPayload("THE REFUSED FILM", java.util.List.of());
    }
}
