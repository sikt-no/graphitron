package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture behind {@code Query.filmLookupValidated}, the suite's one {@code @error} channel
 * carrying a {@code {handler: VALIDATION}} entry alongside a dispatch handler.
 *
 * <p>Two branches by input id:
 * <ul>
 *   <li>{@code id < 0} — throws {@link FilmLookupNotFoundException}; the channel's dispatch
 *       handler routes it, so a mixed VALIDATION-plus-dispatch channel is shown still
 *       dispatching.</li>
 *   <li>otherwise — happy path; returns a populated {@link FilmValidatedLookupPayload}.</li>
 * </ul>
 *
 * <p>The VALIDATION handler validates the classes a {@code @service} method takes, and this method
 * takes a scalar, so there is nothing to validate and the wrapper emits no validation step: what
 * the fixture pins is that a VALIDATION channel whose field binds no class still compiles,
 * dispatches, and resolves the emitted {@code message} body on a live channel.
 */
public final class FilmValidatedLookupService {

    private FilmValidatedLookupService() {}

    public static FilmValidatedLookupPayload lookup(Integer id) {
        if (id != null && id < 0) {
            throw new FilmLookupNotFoundException("validated lookup found no film " + id);
        }
        return new FilmValidatedLookupPayload("THE VALIDATED FILM", java.util.List.of());
    }
}
