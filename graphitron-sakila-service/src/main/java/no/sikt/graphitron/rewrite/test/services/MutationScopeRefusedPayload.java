package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * The payload of {@link MutationScopeService#writeTwiceThenRefuse}. The service never returns one:
 * it throws, and the field's {@code @error} channel claims the throw into the error arm.
 */
public record MutationScopeRefusedPayload(String marker, List<?> errors) {}
