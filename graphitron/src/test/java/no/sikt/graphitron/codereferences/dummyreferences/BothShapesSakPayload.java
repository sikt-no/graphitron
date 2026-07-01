package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test fixture supporting both payload-construction shapes simultaneously: an all-fields
 * constructor <em>and</em> a public no-arg constructor with Java-bean setters. Under
 * canonical-over-bridge precedence the classifier picks
 * {@link no.sikt.graphitron.rewrite.model.PayloadConstructionShape.AllFieldsCtor} (predicate 1
 * runs first; its match short-circuits the walk).
 *
 * <p>Mirrors the SDL field set of {@link SakPayload} ({@code data}, {@code errors}) so the same
 * test SDL exercises both classification paths by swapping the @record class reference.
 */
public final class BothShapesSakPayload {

    private String data;
    private List<Object> errors;

    public BothShapesSakPayload() {}

    public BothShapesSakPayload(String data, List<Object> errors) {
        this.data = data;
        this.errors = errors;
    }

    public void setData(String data) {
        this.data = data;
    }

    public void setErrors(List<Object> errors) {
        this.errors = errors;
    }

    public String getData() {
        return data;
    }

    public List<Object> getErrors() {
        return errors;
    }
}
