package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test fixture bean a {@code @service} returns as a payload whose class carries its own
 * {@code getErrors()} accessor, the shape a Graphitron 9 payload keeps. The payload's
 * {@code errors} field is the error channel all the same; Graphitron never reads this accessor.
 */
public class BeanErrorsPayload {
    public String getData() {
        return null;
    }

    public List<Object> getErrors() {
        return List.of();
    }
}
