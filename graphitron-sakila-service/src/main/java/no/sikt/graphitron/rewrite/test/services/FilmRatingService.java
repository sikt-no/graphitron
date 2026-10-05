package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.test.jooq.Tables.FILM;

/**
 * Multi-tenant fixture: {@code @service} methods whose arguments name the tenant, child services
 * that bind a connection under a tenant-bound parent, and one root over global data. Each reports
 * {@code current_database()} on the connection it was handed, so the execution tier can see which
 * database served it. The tenant databases carry only the tenant-scoped tables, so the film reads
 * select the two columns they hold.
 */
public final class FilmRatingService {

    private FilmRatingService() {}

    /** Rates films named by decoded node ids; the rows come back from the connection it ran on. */
    public static RateFilmsPayload rateFilms(DSLContext dsl, List<FilmRating> ratings) {
        return payload(dsl, ratings.stream().map(r -> r.film().getFilmId()).toList());
    }

    /**
     * Binds no connection: hands back the decoded records themselves, keyed but otherwise empty.
     * The tenant still rides down to them, so their tenant-scoped children route.
     */
    public static RateFilmsPayload rateFilmsOffline(List<FilmRating> ratings) {
        return new RateFilmsPayload(null, ratings.stream().map(FilmRating::film).toList());
    }

    /** {@link #rateFilms} on the error channel. */
    public static RateFilmsCheckedPayload rateFilmsChecked(DSLContext dsl, List<FilmRating> ratings) {
        var payload = rateFilms(dsl, ratings);
        return new RateFilmsCheckedPayload(payload.ranOn(), payload.films(), List.of());
    }

    /** The same over a jOOQ record parameter whose composite key carries the tenant at position 1. */
    public static RateFilmsPayload rateFilmActors(DSLContext dsl, List<FilmActorRecord> actors) {
        return payload(dsl, actors.stream().map(FilmActorRecord::getFilmId).toList());
    }

    /** A root over global data only, marked {@code @globalData}: reports the database it ran on. */
    public static String globalServedBy(DSLContext dsl) {
        return database(dsl);
    }

        /** A child service taking the {@code DSLContext} as a parameter. */
    public static Map<FilmRecord, String> servedBy(Set<FilmRecord> films, DSLContext dsl) {
        return byFilm(films, database(dsl));
    }

    static Map<FilmRecord, String> byFilm(Set<FilmRecord> films, String database) {
        return films.stream().collect(Collectors.toMap(Function.identity(), film -> database));
    }

    static String database(DSLContext dsl) {
        return dsl.fetchValue(DSL.field("current_database()", String.class));
    }

    private static RateFilmsPayload payload(DSLContext dsl, List<Integer> filmIds) {
        var films = dsl.select(FILM.FILM_ID, FILM.TITLE)
            .from(FILM)
            .where(FILM.FILM_ID.in(filmIds))
            .orderBy(FILM.FILM_ID)
            .fetchInto(FilmRecord.class);
        return new RateFilmsPayload(database(dsl), films);
    }
}
