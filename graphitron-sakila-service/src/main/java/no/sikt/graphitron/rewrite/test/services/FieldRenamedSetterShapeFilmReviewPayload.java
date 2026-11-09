package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture: {@code @field(name:)}-renamed sibling of {@link SetterShapeFilmReviewPayload}.
 * Same SDL field set ({@code reviewId}, {@code errors}) but the Java member names diverge, so
 * only {@code @field(name: "reviewIdentifier")} on the SDL data field binds its getter. Graphitron
 * never constructs it: the fetcher returns an {@code Outcome}, the service's instance on success
 * and the claimed errors on a throw, where {@code reviewId} reads null. The {@code errors} field
 * reads the {@code Outcome}, so {@link #getProblems()} is never read, renamed or not.
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
