package no.sikt.graphitron.rewrite.dependency;

import no.sikt.graphitron.rewrite.lint.LintRule;

import java.util.Optional;

/**
 * The foundational runtime dependencies graphitron nudges a consumer to stay current on: the two
 * libraries the generated code is written against, described in the user manual's dependencies page.
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
    JOOQ("org.jooq", "jooq", LintRule.JOOQ_VERSION_LAG);

    private final String groupId;
    private final String artifactId;
    private final LintRule rule;

    WatchedDependency(String groupId, String artifactId, LintRule rule) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.rule = rule;
    }

    public String groupId() {
        return groupId;
    }

    public String artifactId() {
        return artifactId;
    }

    /** The rule a lag advisory for this dependency is tagged with, and therefore suppressible by. */
    public LintRule rule() {
        return rule;
    }

    /** {@code groupId:artifactId}, the form the advisory tells a consumer to bump. */
    public String coordinate() {
        return groupId + ":" + artifactId;
    }

    /** The watched dependency at this coordinate, or empty when the coordinate is not one of them. */
    public static Optional<WatchedDependency> of(String groupId, String artifactId) {
        for (WatchedDependency dep : values()) {
            if (dep.groupId.equals(groupId) && dep.artifactId.equals(artifactId)) {
                return Optional.of(dep);
            }
        }
        return Optional.empty();
    }
}
