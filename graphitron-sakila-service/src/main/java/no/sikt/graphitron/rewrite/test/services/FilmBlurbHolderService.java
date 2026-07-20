package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import org.jooq.DSLContext;

/**
 * Execution-tier fixture for the mixed-source nested type reach. Returns a {@link FilmBlurbHolder}
 * carrying a {@link FilmBlurb} read from the {@code film} row, so the SDL {@code FilmBlurb} type is
 * reached through the producer (class-backed accessor read) while the same type is also embedded as a
 * plain nesting field of the {@code @table Film} parent (generic jOOQ {@code Record} column read).
 * Querying either path resolves {@code FilmBlurb.description} through the one registered fetcher per
 * coordinate, which dispatches on the runtime source shape.
 */
public final class FilmBlurbHolderService {

    private FilmBlurbHolderService() {}

    /** Loads the film's blurb text and wraps it; {@code null} for an unknown id. */
    public static FilmBlurbHolder byId(Integer filmId, DSLContext dsl) {
        if (filmId == null) return null;
        String description = dsl.select(Tables.FILM.DESCRIPTION)
            .from(Tables.FILM)
            .where(Tables.FILM.FILM_ID.eq(filmId))
            .fetchOne(Tables.FILM.DESCRIPTION);
        return description == null ? null : new FilmBlurbHolder(new FilmBlurb(description));
    }
}
