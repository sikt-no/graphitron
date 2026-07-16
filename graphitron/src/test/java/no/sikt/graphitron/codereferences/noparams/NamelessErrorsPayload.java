package no.sikt.graphitron.codereferences.noparams;

import java.util.List;

/**
 * Name-less-POJO witness. A hand-rolled class-backed payload with a single all-fields
 * constructor and an errors slot, deliberately <em>not</em> a record.
 *
 * <p>This package is compiled <b>without</b> the {@code -parameters} flag (see the dedicated
 * {@code test-compile-noparams} execution in {@code graphitron/pom.xml}), so the constructor
 * exposes no reflected parameter names. A {@code @field(name:)} on the errors-shaped slot of a
 * payload backed by this class therefore cannot be resolved by name, and
 * {@code FieldBuilder.buildErrorChannelCtorArm} must reject with the {@code -parameters} guidance
 * rather than silently falling back to the positional rule (the failure mode this witness exists
 * to remove). Kept in its own package, self-contained, so nothing in the {@code -parameters} test
 * tree references it at compile time; the SDL fixture names it only through its service stub's
 * class name string.
 */
public final class NamelessErrorsPayload {

    private final String data;
    private final List<Object> errors;

    public NamelessErrorsPayload(String data, List<Object> errors) {
        this.data = data;
        this.errors = errors;
    }

    public String getData() { return data; }

    public List<Object> getErrors() { return errors; }
}
