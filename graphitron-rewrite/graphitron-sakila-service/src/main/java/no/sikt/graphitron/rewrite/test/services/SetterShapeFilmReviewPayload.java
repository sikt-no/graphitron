package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * R154 fixture: mutable-bean sibling of {@link FilmReviewPayload}. Same SDL field set
 * ({@code reviewId}, {@code errors}) but no all-fields constructor. The carrier classifier
 * resolves R154's setter-shape predicate against this class: public no-arg constructor plus a
 * Java-bean setter ({@code setReviewId}, {@code setErrors}) per SDL field. The
 * {@code MutationServiceRecordField} fetcher emits the catch-arm payload-factory in
 * setter-shape form ({@code errors -> { var p = new ...; p.setReviewId(null); p.setErrors(errors);
 * return p; }}).
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
