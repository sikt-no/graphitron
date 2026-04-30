package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test stand-in for the generated {@code <outputPackage>.schema.GraphitronError} marker.
 * Lives under the dummy-references package so test fixtures can use it as the element type of
 * the {@code errors} list parameter on a payload's all-fields constructor; the carrier
 * classifier identifies the errors slot by simple name {@code "GraphitronError"}.
 *
 * <p>The full marker-interface enforcement check (verifying every {@code @error} class
 * implements {@code GraphitronError}) lands later as a separate piece of
 * error-handling-parity.md.
 */
public interface GraphitronError {
    List<String> path();
    String message();
}
