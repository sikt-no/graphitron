package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * Test fixture record a {@code @service} returns as a payload with a data component only: the
 * payload's {@code errors} field has no member on the class, which is the shape the error channel
 * asks for, since the errors arrive on the {@code Outcome} rather than through the payload.
 */
public record DataOnlyPayload(String data) {}
