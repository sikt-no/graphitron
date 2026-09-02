package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.model.config.ObservedVersion;
import no.sikt.graphitron.model.config.WatchedDependency;
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
 * what this pins is the scope allow-list and which coordinates count as a watched library.
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
            entry(WatchedDependency.JOOQ, List.of(new ObservedVersion("org.jooq:jooq", "3.19.15"))));
    }

    @ParameterizedTest(name = "jOOQ under {0} is observed as the same library")
    @ValueSource(strings = {
        // The current baseline JDK's commercial distribution, and the trial of the same.
        "org.jooq.pro", "org.jooq.trial",
        // The older baselines still supported. The suffix rotates with each baseline bump, which is
        // why this matches a prefix: an enumerated list would go stale into silence.
        "org.jooq.pro-java-11", "org.jooq.pro-java-8", "org.jooq.trial-java-11",
    })
    void everyCommercialEditionIsTheSameWatchedLibrary(String groupId) {
        var versions = AbstractRewriteMojo.decodeDependencyVersions(
            List.of(artifact(groupId, "jooq", "3.19.15", "compile")), List.of());

        assertThat(versions.observed())
            .as("a commercial consumer resolves jOOQ, and the coordinate they resolved travels with it")
            .containsExactly(entry(WatchedDependency.JOOQ,
                List.of(new ObservedVersion(groupId + ":jooq", "3.19.15"))));
    }

    @ParameterizedTest(name = "jooq-codegen under {0} stays unmatched")
    @ValueSource(strings = {"org.jooq", "org.jooq.pro", "org.jooq.pro-java-11", "org.jooq.trial"})
    void theCodegenArtifactIsNotTheRuntimeLibraryUnderAnyEdition(String groupId) {
        var versions = AbstractRewriteMojo.decodeDependencyVersions(
            List.of(artifact(groupId, "jooq-codegen", "3.20.11", "compile")), List.of());

        assertThat(versions.observed())
            .as("pinning the artifact id exactly is what keeps the group-id prefix safe")
            .isEmpty();
    }

    @Test
    void aGroupIdMerelyStartingWithTheOpenSourceOneIsNotAnEdition() {
        // The prefixes are `org.jooq.pro` and `org.jooq.trial`, not a bare `org.jooq`: an unrelated
        // future coordinate under the same namespace is not this library.
        var versions = AbstractRewriteMojo.decodeDependencyVersions(
            List.of(artifact("org.jooq.something-else", "jooq", "3.19.15", "compile")), List.of());

        assertThat(versions.observed()).isEmpty();
    }

    @Test
    void bothEditionsAtOnceAreBothCarried() {
        // Maven mediates per coordinate, not per library, so both survive resolution and the decode
        // must not drop one; which of them the advisory speaks about is decided behind this boundary.
        var versions = AbstractRewriteMojo.decodeDependencyVersions(
            List.of(jooq("3.15.4", "compile"),
                    artifact("org.jooq.pro", "jooq", "3.19.15", "compile")),
            List.of());

        assertThat(versions.observed().get(WatchedDependency.JOOQ))
            .containsExactlyInAnyOrder(
                new ObservedVersion("org.jooq:jooq", "3.15.4"),
                new ObservedVersion("org.jooq.pro:jooq", "3.19.15"));
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
            List.of(artifact("org.slf4j", "slf4j-api", "2.0.17", "compile")), List.of());

        assertThat(versions.observed())
            .as("an absent coordinate is not a lagging one")
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
            entry(WatchedDependency.JOOQ, List.of(new ObservedVersion("org.jooq:jooq", "3.19.15"))),
            entry(WatchedDependency.GRAPHQL_JAVA,
                List.of(new ObservedVersion("com.graphql-java:graphql-java", "22.3"))));
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
