package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Mutable-bean sibling of {@link FilmReviewPayload}. Same SDL field set
 * ({@code reviewId}, {@code errors}) but no all-fields constructor: a public no-arg constructor
 * and a Java-bean setter and getter per field, the shape a Graphitron 9 payload keeps. Graphitron
 * never constructs it: the {@code MutationServiceRecordField} fetcher returns an {@code Outcome},
 * the service's instance on success and the claimed errors on a throw. Its own
 * {@link #getErrors()} is never read; the payload's {@code errors} field reads the
 * {@code Outcome}.
 */
public final class SetterShapeFilmReviewPayload {

    private Integer reviewId;
    private List<?> errors;

    public SetterShapeFilmReviewPayload() {}

    public void setReviewId(Integer reviewId) {
        this.reviewId = reviewId;
    }

    public void setErrors(List<?> errors) {
        this.errors = errors;
    }

    public Integer getReviewId() {
        return reviewId;
    }

    public List<?> getErrors() {
        return errors;
    }
}
