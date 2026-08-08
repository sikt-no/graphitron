package no.sikt.graphitron.rewrite.dependency;

import java.util.Map;

/**
 * The version facts the dependency-currency nudge decides on: what the consumer's build resolved for
 * each {@link WatchedDependency}, and what graphitron itself was built against.
 *
 * <p>Plain strings, no Maven types. {@code org.apache.maven.artifact.Artifact} and
 * {@code MavenProject} are external untyped input and must not cross into this module; the mojo
 * decodes both artifact sets into this carrier and threads it through
 * {@link no.sikt.graphitron.rewrite.RewriteContext}, the same route the {@code <lint>} and
 * {@code <sessionState>} configuration already takes. Every non-Maven caller (unit tiers, the LSP,
 * tests) gets {@link #empty()} and the advisory stays silent.
 *
 * <p>Either side of a pair may be absent, and both absences are silence rather than a defect:
 * a coordinate missing from {@code observed} is a consumer who does not carry that dependency on
 * the scopes the generated code compiles against, and a coordinate missing from {@code reference}
 * is graphitron unable to read its own version, which an advisory has no business failing over.
 *
 * @param observed  resolved version per watched dependency, off the consumer's dependency graph
 * @param reference resolved version per watched dependency, off graphitron's own plugin realm
 */
public record DependencyVersions(
    Map<WatchedDependency, String> observed,
    Map<WatchedDependency, String> reference
) {
    private static final DependencyVersions EMPTY = new DependencyVersions(Map.of(), Map.of());

    public DependencyVersions {
        observed = observed == null ? Map.of() : Map.copyOf(observed);
        reference = reference == null ? Map.of() : Map.copyOf(reference);
    }

    /** No version facts at all, so every advisory is silent. The default for non-Maven callers. */
    public static DependencyVersions empty() {
        return EMPTY;
    }
}
