package no.sikt.graphitron.rewrite;

/**
 * A {@link TenantServiceStub} JavaBean whose only tenant carrier is a plain scalar, as a
 * create-mutation's input is: no row or id exists yet, so the input names the tenant by value.
 */
public class RateByFilmIdBean {
    private Integer filmId;
    private Integer rating;

    public void setFilmId(Integer filmId) {
        this.filmId = filmId;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }
}
