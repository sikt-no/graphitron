package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test stand-in for a developer-supplied {@code @error} backing class that has the canonical
 * {@code (List<String> path, String message)} constructor but does <strong>not</strong>
 * implement the {@code GraphitronError} marker interface. Exercises the rejection arm of
 * {@code TypeBuilder.validateImplementsGraphitronError}: the generated
 * {@code ErrorRouter.Mapping.build} factory returns {@code GraphitronError}, so a backing class
 * that doesn't implement the marker would produce uncompilable generated source.
 */
public record MissingMarkerErrorBackingFixture(List<String> path, String message) {
}
