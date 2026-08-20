package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture for query-side {@code @error} channel generation, end-to-end. The {@code lookup} method drives the
 * {@code GraphitronSchema.java} emit path that broke in production (the
 * {@code buildErrorTypeFieldFetchers} cast disambiguation + {@code env.getSource()} call) by
 * forcing both compile-spec (sakila-example {@code mvn compile}) and execute-spec (real
 * PostgreSQL round-trip via {@code GraphQLQueryTest}) to exercise the synthesized per-
 * {@code @error}-type {@code DataFetcher}s for {@code path} and {@code message}, the union's
 * source-class-instanceof {@code TypeResolver}, and the per-fetcher try/catch route through
 * {@code ErrorRouter.dispatch}.
 *
 * <p>Four branches by input id:
 * <ul>
 *   <li>{@code id < 0} — throws {@link FilmLookupInvalidIdException}; dispatch routes to the
 *       {@code FilmLookupInvalid} {@code @error} type, whose handler carries a
 *       {@code description:} so the client sees the authored string in place of the raw
 *       exception message.</li>
 *   <li>{@code id == 0} — throws {@link FilmLookupNotFoundException}; dispatch routes to the
 *       {@code FilmLookupNotFound} {@code @error} type, whose handler carries no
 *       {@code description:} and so keeps reading {@code getMessage()}. The pair is what makes
 *       the override observably per-handler rather than per-channel.</li>
 *   <li>{@code id == 777} — throws {@link FilmLookupClientFacingException}, a source that is a
 *       {@code Throwable} and a {@code GraphQLError} at once; dispatch routes to the
 *       {@code FilmLookupClientFacing} {@code @error} type, whose handler also carries a
 *       {@code description:}, so which of the emitted {@code message} arms wins is observable.</li>
 *   <li>any other {@code id > 0} — happy path; returns a populated {@link FilmLookupPayload}.</li>
 * </ul>
 *
 * <p>No DB round-trip — the fixture's purpose is to exercise the schema-emit codepaths, and a
 * hand-rolled body keeps the test deterministic and free of incidental setup.
 */
public final class FilmLookupService {

    private FilmLookupService() {}

    public static FilmLookupPayload lookup(Integer id) {
        if (id == null) {
            throw new FilmLookupInvalidIdException("id is null");
        }
        if (id < 0) {
            throw new FilmLookupInvalidIdException("invalid id: " + id, id);
        }
        if (id == 0) {
            throw new FilmLookupNotFoundException("film " + id + " not found");
        }
        if (id == 777) {
            throw new FilmLookupClientFacingException("raw getMessage from the both-shapes source");
        }
        return new FilmLookupPayload("THE LOOKED-UP FILM", java.util.List.of());
    }
}
