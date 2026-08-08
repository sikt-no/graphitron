package no.sikt.graphitron.rewrite.dependency;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 * a dependency missing from {@code observed} is a consumer who does not carry it on the scopes the
 * generated code compiles against, and one missing from {@code reference} is graphitron unable to
 * read its own version, which an advisory has no business failing over.
 *
 * <p><b>The two sides are not symmetric.</b> {@code observed} is a list per dependency because a
 * library can be resolved at more than one coordinate (jOOQ's editions), and each observation carries
 * the coordinate it was resolved under so the advisory can name a coordinate the consumer's build
 * actually has. {@code reference} is a bare version per dependency: graphitron's own plugin realm
 * resolves one of each, and the advisory reports the reference version number and nothing else about
 * where it came from, so a coordinate there would be carried for no reader.
 *
 * @param observed  resolved versions per watched dependency, off the consumer's dependency graph
 * @param reference resolved version per watched dependency, off graphitron's own plugin realm
 */
public record DependencyVersions(
    Map<WatchedDependency, List<ObservedVersion>> observed,
    Map<WatchedDependency, String> reference
) {
    private static final DependencyVersions EMPTY = new DependencyVersions(Map.of(), Map.of());

    public DependencyVersions {
        observed = observed == null
            ? Map.of()
            : observed.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> List.copyOf(e.getValue())));
        reference = reference == null ? Map.of() : Map.copyOf(reference);
    }

    /** No version facts at all, so every advisory is silent. The default for non-Maven callers. */
    public static DependencyVersions empty() {
        return EMPTY;
    }

    /** Every coordinate this dependency was resolved at, empty when the consumer carries none. */
    public List<ObservedVersion> observedFor(WatchedDependency dep) {
        return observed.getOrDefault(dep, List.of());
    }
}
