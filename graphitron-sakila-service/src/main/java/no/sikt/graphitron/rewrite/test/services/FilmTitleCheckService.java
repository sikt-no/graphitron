package no.sikt.graphitron.rewrite.test.services;

import org.jooq.Record1;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Fixture for an {@code @error} object reached below a list: a child {@code @service} on
 * {@code Film} whose payload carries an errors field, so a matched exception lands in
 * {@code films[i].titleCheck.errors} rather than in a root field's payload. The execute tier reads
 * the error's {@code path} off it, which names the payload-holding field with the list index of
 * the film it hangs off.
 *
 * <p>Batched like every child {@code @service}: one call per request for every film the query
 * reached. A batch holding {@link #REFUSED_FILM_ID} throws {@link FilmLookupNotFoundException},
 * which fails the whole batch, so each film in it answers the error; any other batch returns a
 * populated payload per film. No DB round-trip, so the fixture stays deterministic.
 */
public final class FilmTitleCheckService {

    /** The film id whose presence in a batch makes the check throw. */
    public static final int REFUSED_FILM_ID = 3;

    private FilmTitleCheckService() {}

    public static Map<Record1<Integer>, FilmTitleCheckPayload> check(Set<Record1<Integer>> filmIds) {
        if (filmIds.stream().anyMatch(key -> key.value1() == REFUSED_FILM_ID)) {
            throw new FilmLookupNotFoundException("film " + REFUSED_FILM_ID + " refused the title check");
        }
        Map<Record1<Integer>, FilmTitleCheckPayload> result = new LinkedHashMap<>();
        for (Record1<Integer> key : filmIds) {
            result.put(key, new FilmTitleCheckPayload("checked " + key.value1(), null));
        }
        return result;
    }
}
