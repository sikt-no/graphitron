package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture for an {@code @error} type's extra fields typed as a GraphQL enum and as an object, read
 * through getters returning a Java enum, a list of one and a plain class. graphql-java writes a Java
 * enum constant by its name, so none of them is {@code ERROR_FIELD_UNREADABLE}. Never thrown.
 */
public class FilmLookupRatedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The rating the lookup was refused for. */
    public enum Rating { G, PG }

    /** What the refused lookup was about. */
    public record Detail(String title) {}

    private final Rating rating;

    public FilmLookupRatedException(String message, Rating rating) {
        super(message);
        this.rating = rating;
    }

    public Rating getRating() {
        return rating;
    }

    public Rating getMaybeRating() {
        return rating;
    }

    public List<Rating> getRatings() {
        return List.of(rating);
    }

    public Detail getDetail() {
        return new Detail("film");
    }
}
