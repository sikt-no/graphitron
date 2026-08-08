package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.dependency.WatchedDependency;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Covers the Maven boundary's one job: turning {@link Artifact} objects into
 * {@code (coordinate, version-string)} pairs for the dependency-currency nudge. The comparison and
 * the message live behind the boundary and are covered by {@code DependencyVersionWarningsTest};
 * what this pins is the scope allow-list.
 *
 * <p>The {@code runtime} row is the load-bearing one. {@code generate} / {@code validate} resolve
 * {@code {compile, system, provided}} and {@code dev} resolves those plus {@code {runtime, test}},
 * so a deny-list of {@code test} alone would let a runtime-scoped coordinate be observed under
 * {@code graphitron:dev} and not under {@code graphitron:generate}: the same project saying two
 * different things depending on which goal ran. What this tier cannot pin is Maven's resolution
 * itself, so goal-invariance rests on the allow-list naming exactly the scopes
 * {@code ResolutionScope.COMPILE} resolves.
 */
class DependencyVersionDecodeTest {

    private static Artifact artifact(String groupId, String artifactId, String version, String scope) {
        return new DefaultArtifact(groupId, artifactId, version, scope, "jar", null,
            new DefaultArtifactHandler("jar"));
    }

    private static Artifact jooq(String version, String scope) {
        return artifact("org.jooq", "jooq", version, scope);
    }

    @ParameterizedTest(name = "{0}-scoped jOOQ is observed")
    @ValueSource(strings = {"compile", "provided", "system"})
    void scopesTheGeneratedCodeCompilesAgainstAreObserved(String scope) {
        var versions = AbstractRewriteMojo.decodeDependencyVersions(
            List.of(jooq("3.19.15", scope)), List.of());

        assertThat(versions.observed()).containsExactly(
            entry(WatchedDependency.JOOQ, "3.19.15"));
    }

    @ParameterizedTest(name = "{0}-scoped jOOQ is not observed")
    @ValueSource(strings = {"runtime", "test"})
    void scopesTheGeneratedCodeIsNotBuiltAgainstAreNotObserved(String scope) {
        var versions = AbstractRewriteMojo.decodeDependencyVersions(
            List.of(jooq("3.19.15", scope)), List.of());

        assertThat(versions.observed())
            .as("a coordinate visible only at " + scope + " is not one the generated sources compile against")
            .isEmpty();
    }

    @Test
    void unwatchedCoordinatesAndAbsentOnesYieldNothing() {
        var versions = AbstractRewriteMojo.decodeDependencyVersions(
            List.of(artifact("org.slf4j", "slf4j-api", "2.0.17", "compile"),
                    artifact("org.jooq", "jooq-codegen", "3.20.11", "compile")),
            List.of());

        assertThat(versions.observed())
            .as("an absent coordinate is not a lagging one, and jooq-codegen is not jooq")
            .isEmpty();
    }

    @Test
    void bothCoordinatesAreDecodedFromBothSides() {
        var versions = AbstractRewriteMojo.decodeDependencyVersions(
            List.of(jooq("3.19.15", "compile"),
                    artifact("com.graphql-java", "graphql-java", "22.3", "provided")),
            List.of(jooq("3.20.11", "runtime"),
                    artifact("com.graphql-java", "graphql-java", "25.0", "runtime")));

        assertThat(versions.observed()).containsOnly(
            entry(WatchedDependency.JOOQ, "3.19.15"),
            entry(WatchedDependency.GRAPHQL_JAVA, "22.3"));
        assertThat(versions.reference())
            .as("the plugin realm's scopes are a fact of graphitron's own build and are not filtered")
            .containsOnly(
                entry(WatchedDependency.JOOQ, "3.20.11"),
                entry(WatchedDependency.GRAPHQL_JAVA, "25.0"));
    }

    @Test
    void absentArtifactSetsDecodeToNoVersionFacts() {
        // A hand-built mojo (every unit-tier caller in this module) has no project and no plugin
        // descriptor; the nudge must go quiet rather than throw.
        var versions = AbstractRewriteMojo.decodeDependencyVersions(null, null);

        assertThat(versions.observed()).isEmpty();
        assertThat(versions.reference()).isEmpty();
    }
}
