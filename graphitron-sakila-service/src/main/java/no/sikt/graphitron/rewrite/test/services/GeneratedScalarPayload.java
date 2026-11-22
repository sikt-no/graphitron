package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture for a wrong generated scalar value routed through {@code @error}: the payload
 * {@link GeneratedScalarService#endType} returns, whose {@code errors} field the client exception
 * refusing the value is claimed into. Graphitron never constructs this record.
 */
public record GeneratedScalarPayload(String endType, List<?> errors) {}
