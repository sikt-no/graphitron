package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.test.jooq.Tables.FILM;
import static no.sikt.graphitron.rewrite.test.jooq.Tables.LANGUAGE;

/**
 * Multi-tenant fixture: {@code @service} methods whose arguments name the tenant, child services
 * that bind a connection under a tenant-bound parent, and one root over global data. Each reports
 * {@code current_database()} on the connection it was handed, so the execution tier can see which
 * database served it. The tenant databases carry the tenant-scoped tables plus slim copies of the
 * global {@code language} and {@code store} tables, so the film reads select the two columns they
 * hold.
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

    /**
     * Rates films named by a plain {@code filmId} the schema marks {@code @tenant}: the call is
     * routed on that value, so the rows come back from the tenant it names.
     */
    public static RateFilmsPayload rateFilmsByFilmId(DSLContext dsl, List<FilmIdRating> ratings) {
        return payload(dsl, ratings.stream().map(FilmIdRating::getFilmId).toList());
    }

    /** The same with a decoded film beside the marked value; both name the tenant. */
    public static RateFilmsPayload rateFilmsByFilmIdAndFilm(DSLContext dsl,
                                                            List<FilmIdAndFilmRating> ratings) {
        return payload(dsl, ratings.stream().map(FilmIdAndFilmRating::filmId).toList());
    }

    /** A root over global data only, marked {@code @globalData}: reports the database it ran on. */
    public static String globalServedBy(DSLContext dsl) {
        return database(dsl);
    }

    /** {@link #rateFilms} whose payload also hands back a global row, keyed on language 1. */
    public static RatedFilmsLanguagePayload rateFilmsWithLanguage(DSLContext dsl, List<FilmRating> ratings) {
        var payload = rateFilms(dsl, ratings);
        return new RatedFilmsLanguagePayload(payload.ranOn(), payload.films(), languageKey(1));
    }

    /**
     * {@link #globalServedBy} whose payload also hands back a global row, keyed on language 1: the
     * payload of a default-source producer.
     */
    public static GlobalLanguagePayload globalLanguage(DSLContext dsl) {
        return new GlobalLanguagePayload(database(dsl), languageKey(1));
    }

    /**
     * A root over global data returning the language rows its connection holds; graphitron re-reads
     * them on the same connection.
     */
    public static List<LanguageRecord> languagesOnConnection(DSLContext dsl) {
        return dsl.selectFrom(LANGUAGE).orderBy(LANGUAGE.LANGUAGE_ID).fetch();
    }

    /** A child service on a global parent, taking the {@code DSLContext} as a parameter. */
    public static Map<LanguageRecord, String> languageServedBy(Set<LanguageRecord> languages, DSLContext dsl) {
        String database = database(dsl);
        return languages.stream().collect(Collectors.toMap(Function.identity(), language -> database));
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

    private static LanguageRecord languageKey(int languageId) {
        var language = new LanguageRecord();
        language.setLanguageId(languageId);
        return language;
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
