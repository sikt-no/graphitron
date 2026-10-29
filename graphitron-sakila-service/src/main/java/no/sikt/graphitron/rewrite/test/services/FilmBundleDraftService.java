package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture behind {@code Mutation.countFilmBundleDraft}: a {@code @service} mutation whose argument
 * binds {@link FilmBundleDraft}, under the same VALIDATION-handled error channel as
 * {@code countFilmDrafts}. What it pins is the {@code path} of each validation error: SDL input
 * fields rather than Java members, the element index for a list member, and the argument alone for
 * a class-level constraint. With a valid bundle the method runs and counts its films.
 */
public final class FilmBundleDraftService {

    private FilmBundleDraftService() {}

    public static FilmDraftsCountedPayload count(FilmBundleDraft bundle) {
        return new FilmDraftsCountedPayload(bundle.films() == null ? 0 : bundle.films().size(), List.of());
    }
}
