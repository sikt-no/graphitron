package no.sikt.graphitron.rewrite.dependency;

import no.sikt.graphitron.rewrite.lint.LintRule;

import java.util.List;
import java.util.Optional;

/**
 * The foundational runtime dependencies graphitron nudges a consumer to stay current on: the two
 * libraries the generated code is written against, described in the user manual's dependencies page.
 *
 * <p>A constant is a <em>library</em>, not a coordinate. jOOQ ships one release line under several
 * group ids, one per edition, so a library can be resolved at more than one coordinate and the
 * coordinate has to travel with the observation ({@link ObservedVersion}) rather than being read back
 * off the constant.
 *
 * <p>The coordinates live here, in the interior, rather than at the Maven boundary that reads them.
 * The boundary's whole job is turning artifacts into {@code (coordinate, version-string)} pairs, so
 * it asks {@link #of(String, String)} which coordinates are interesting and never carries its own
 * copy of the list; adding a third dependency is one enum constant plus one {@link LintRule}.
 *
 * <p>A rule per dependency rather than one shared rule, because suppression is per rule id: a
 * consumer held on an old jOOQ line by a licence or a platform BOM can silence that nudge and keep
 * the graphql-java one.
 */
public enum WatchedDependency {
    GRAPHQL_JAVA("com.graphql-java", "graphql-java", LintRule.GRAPHQL_JAVA_VERSION_LAG),

    /**
     * jOOQ, at any edition. The commercial and trial distributions keep the artifact id and vary the
     * group id, and the varying part is not a fixed set: {@code org.jooq.pro} tracks whatever the
     * current baseline JDK is, and each baseline bump rotates a new {@code -java-<n>} into the
     * supported set. Matching a prefix rather than an enumerated list is what keeps a consumer from
     * dropping out of the advisory's sight the moment they move their JDK baseline, which would fail
     * the same silent way this nudge exists to fix.
     */
    JOOQ("org.jooq", "jooq", LintRule.JOOQ_VERSION_LAG, "org.jooq.pro", "org.jooq.trial");

    private final String groupId;
    private final String artifactId;
    private final LintRule rule;
    private final List<String> editionGroupIdPrefixes;

    WatchedDependency(String groupId, String artifactId, LintRule rule, String... editionGroupIdPrefixes) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.rule = rule;
        this.editionGroupIdPrefixes = List.of(editionGroupIdPrefixes);
    }

    /** The rule a lag advisory for this dependency is tagged with, and therefore suppressible by. */
    public LintRule rule() {
        return rule;
    }

    /**
     * Whether this coordinate resolves this library, at any edition.
     *
     * <p>The artifact id must match exactly, and that exactness is what keeps the group-id prefix
     * safe: {@code jooq-codegen} and its commercial twins share the prefix but are not the runtime
     * library the generated sources are built against.
     */
    private boolean matches(String groupId, String artifactId) {
        if (!this.artifactId.equals(artifactId)) {
            return false;
        }
        return this.groupId.equals(groupId)
            || editionGroupIdPrefixes.stream().anyMatch(groupId::startsWith);
    }

    /** The watched dependency at this coordinate, or empty when the coordinate is not one of them. */
    public static Optional<WatchedDependency> of(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) {
            return Optional.empty();
        }
        for (WatchedDependency dep : values()) {
            if (dep.matches(groupId, artifactId)) {
                return Optional.of(dep);
            }
        }
        return Optional.empty();
    }
}
