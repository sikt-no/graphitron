package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test stand-in for a developer-supplied {@code @error} backing class that has the canonical
 * {@code (List<String> path, String message)} constructor but does <strong>not</strong>
 * expose the {@code path()} / {@code message()} accessors graphql-java's
 * {@code PropertyDataFetcher} reads at runtime. Exercises the rejection arm of
 * {@code TypeBuilder.validatePathMessageAccessors}: the runtime would fail the first request
 * if the schema validation didn't catch this loudly at classify time.
 *
 * <p>Storage and accessors are deliberately renamed to {@code p} / {@code m} so neither
 * {@code path()} nor {@code message()} resolves via reflection.
 */
public final class MissingAccessorErrorBackingFixture {
    private final List<String> p;
    private final String m;

    public MissingAccessorErrorBackingFixture(List<String> path, String message) {
        this.p = path;
        this.m = message;
    }

    public List<String> getP() { return p; }

    public String getM() { return m; }
}
