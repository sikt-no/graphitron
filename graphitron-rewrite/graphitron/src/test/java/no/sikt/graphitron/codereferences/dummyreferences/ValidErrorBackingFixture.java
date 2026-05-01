package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test stand-in for a developer-supplied {@code @error} backing class. Has the canonical
 * {@code (List<String> path, String message)} constructor that the
 * {@code ErrorRouter.Mapping.build} call site invokes at dispatch (per the
 * {@code error-handling-parity} spec's payload-factory contract); also exposes the
 * {@code path()} and {@code message()} accessors that graphql-java's
 * {@code PropertyDataFetcher} reads to serialise the error into the response.
 *
 * <p>Used by {@code ErrorChannelClassificationTest} and friends to exercise the
 * {@code TypeBuilder.validatePathMessageConstructor} and
 * {@code TypeBuilder.validatePathMessageAccessors} reflection checks.
 */
public record ValidErrorBackingFixture(List<String> path, String message) {
}
