package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test fixture for the mutable-bean payload shape, sibling of {@link SakPayload}: same SDL
 * field set ({@code data}, {@code errors}) but no all-fields constructor. The classifier
 * resolves this class via the setter-shape predicate: public no-arg constructor plus a
 * Java-bean setter ({@code setData}, {@code setErrors}) per SDL field. The catch-arm
 * payload-factory emits {@code errors -> { var p = new SetterShapeSakPayload();
 * p.setErrors(errors); p.setData(null); return p; }}.
 */
public final class SetterShapeSakPayload {

    private String data;
    private List<Object> errors;

    public SetterShapeSakPayload() {}

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
