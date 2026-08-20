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
 * <p>The wrapper runs {@code jakarta.validation.Validator.validate} on the {@code id} argument
 * before this method, because the channel carries a VALIDATION handler. The generated input the
 * pre-step walks declares no constraint annotations, so the walk yields no violations and the
 * body always runs: what the fixture pins is that the pre-step, the generated
 * {@code ConstraintViolations} helper and the emitted {@code message} body's {@code GraphQLError}
 * arm all compile and execute on a live channel. An assertion on an interpolated violation
 * message is not constructible today, because nothing attaches constraints to the type the
 * pre-step validates.
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
