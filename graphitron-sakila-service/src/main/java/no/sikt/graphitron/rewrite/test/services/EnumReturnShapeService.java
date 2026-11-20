package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.enums.MpaaRating;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import org.jooq.DSLContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fixtures for a {@code @service} whose field is an enum. {@link #ratingByFilm} is the accepted
 * batched shape, a map from the parent's film record to the jOOQ enum its rating column holds, and
 * is executed; the rest are the containers the batch cannot read an enum per key from, refused at
 * build time and never called.
 */
public final class EnumReturnShapeService {

    private EnumReturnShapeService() {}

    /** Each film's rating, read for the keys handed in. */
    public static Map<FilmRecord, MpaaRating> ratingByFilm(Set<FilmRecord> films, DSLContext dsl) {
        Map<Integer, MpaaRating> byId = dsl.select(Tables.FILM.FILM_ID, Tables.FILM.RATING)
            .from(Tables.FILM)
            .where(Tables.FILM.FILM_ID.in(films.stream().map(FilmRecord::getFilmId).toList()))
            .fetchMap(Tables.FILM.FILM_ID, Tables.FILM.RATING);
        Map<FilmRecord, MpaaRating> out = new LinkedHashMap<>();
        for (FilmRecord film : films) {
            out.put(film, byId.get(film.getFilmId()));
        }
        return out;
    }

    /** One rating per key, in the keys' order. */
    public static List<MpaaRating> ratingInOrder(List<FilmRecord> films) {
        return List.of();
    }

    /** Several ratings per key. */
    public static Map<FilmRecord, List<MpaaRating>> ratingsByFilm(Set<FilmRecord> films) {
        return Map.of();
    }

    /** A map naming neither its key nor its value. */
    @SuppressWarnings("rawtypes")
    public static Map ratingRaw(Set<FilmRecord> films) {
        return Map.of();
    }

    /** A map whose value is the caller's to choose. */
    public static <T> Map<FilmRecord, T> ratingGeneric(Set<FilmRecord> films) {
        return Map.of();
    }

    /** A map keyed by the film's id rather than by the records handed in. */
    public static Map<Integer, MpaaRating> ratingByFilmId(Set<FilmRecord> films) {
        return Map.of();
    }

    /** The one rating a root field answers. */
    public static MpaaRating topRating() {
        return MpaaRating.G;
    }
}
