package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Multi-tenant fixture: one row of {@link FilmRatingService#rateFilms}. Both record members are
 * decoded from {@code @nodeId(typeName: "Film")} ids, and {@code film_id} is the tenant, so each
 * names the tenant the call runs on; {@code alsoFilm} is optional, and an omitted one names none.
 */
public record FilmRating(FilmRecord film, FilmRecord alsoFilm, String rating) {
}
