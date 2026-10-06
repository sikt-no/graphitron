package no.sikt.graphitron.rewrite.test.services;

/**
 * Multi-tenant fixture: one row of {@link FilmRatingService#rateFilmsByFilmId}, a hand-written
 * bean whose only tenant carrier is the plain {@code filmId} the schema marks {@code @tenant}, as a
 * create-mutation's input is when no row or id exists yet to name the tenant.
 */
public class FilmIdRating {
    private Integer filmId;
    private Integer rating;

    public Integer getFilmId() {
        return filmId;
    }

    public void setFilmId(Integer filmId) {
        this.filmId = filmId;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }
}
