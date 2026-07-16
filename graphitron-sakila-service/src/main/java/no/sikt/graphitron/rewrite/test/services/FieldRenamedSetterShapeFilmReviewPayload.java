package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * R201 fixture: {@code @field(name:)}-renamed sibling of {@link SetterShapeFilmReviewPayload}.
 * Same SDL field set ({@code reviewId}, {@code errors}) but the Java member names diverge, so
 * only {@code @field(name: "reviewIdentifier")} / {@code @field(name: "problems")} on the SDL
 * fields can bind the setters. The carrier classifier resolves the mutable-bean construction shape
 * against the remapped setter names ({@code setReviewIdentifier}, {@code setProblems}); the emitter
 * produces the catch-arm payload-factory as
 * {@code errors -> { var p = new ...; p.setProblems(errors); p.setReviewIdentifier(null);
 * return p; }}. The getters carry the same remapped names so the read side (already honoring
 * {@code @field(name:)} pre-R201) resolves the payload's data fields.
 */
public final class FieldRenamedSetterShapeFilmReviewPayload {

    private Integer reviewIdentifier;
    private List<?> problems;

    public FieldRenamedSetterShapeFilmReviewPayload() {}

    public void setReviewIdentifier(Integer reviewIdentifier) {
        this.reviewIdentifier = reviewIdentifier;
    }

    public void setProblems(List<?> problems) {
        this.problems = problems;
    }

    public Integer getReviewIdentifier() {
        return reviewIdentifier;
    }

    public List<?> getProblems() {
        return problems;
    }
}
