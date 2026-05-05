package no.sikt.graphitron.rewrite.test.services;

/**
 * R12 fixture: query-side {@code @error} end-to-end. The {@code lookup} method drives the
 * {@code GraphitronSchema.java} emit path that broke in production (the
 * {@code buildErrorTypeFieldFetchers} cast disambiguation + {@code env.getSource()} call) by
 * forcing both compile-spec (sakila-example {@code mvn compile}) and execute-spec (real
 * PostgreSQL round-trip via {@code GraphQLQueryTest}) to exercise the synthesized per-
 * {@code @error}-type {@code DataFetcher}s for {@code path} and {@code message}, the union's
 * source-class-instanceof {@code TypeResolver}, and the per-fetcher try/catch route through
 * {@code ErrorRouter.dispatch}.
 *
 * <p>Three branches by input id:
 * <ul>
 *   <li>{@code id < 0} — throws {@link FilmLookupInvalidIdException}; dispatch routes to the
 *       {@code FilmLookupInvalid} {@code @error} type.</li>
 *   <li>{@code id == 0} — throws {@link FilmLookupNotFoundException}; dispatch routes to the
 *       {@code FilmLookupNotFound} {@code @error} type.</li>
 *   <li>{@code id > 0} — happy path; returns a populated {@link FilmLookupPayload}.</li>
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
            throw new FilmLookupInvalidIdException("invalid id: " + id);
        }
        if (id == 0) {
            throw new FilmLookupNotFoundException("film " + id + " not found");
        }
        return new FilmLookupPayload("THE LOOKED-UP FILM", java.util.List.of());
    }
}
