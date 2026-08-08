package no.sikt.graphitron.rewrite.dependency;

import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of the dependency-currency nudge: the minor-line predicate and the message
 * shaping together, which is where the behaviour lives. The mojo only decodes artifacts into
 * {@link DependencyVersions}, so the decision is asserted directly here rather than through a
 * pipeline run; {@code LintSuppressionPipelineTest} covers the routing.
 *
 * <p>Silence is the interesting output under this design, so the silence cases are enumerated
 * rather than left implicit: a warning that fires by design has a false positive as its whole
 * failure mode, and one that fires wrongly discredits every other warning on the channel.
 */
@UnitTier
class DependencyVersionWarningsTest {

    private static Map<WatchedDependency, String> only(WatchedDependency dep, String version) {
        var map = new EnumMap<WatchedDependency, String>(WatchedDependency.class);
        map.put(dep, version);
        return map;
    }

    private static java.util.List<BuildWarning> warnings(String observedJooq, String referenceJooq) {
        return DependencyVersionWarnings.forVersions(new DependencyVersions(
            only(WatchedDependency.JOOQ, observedJooq), only(WatchedDependency.JOOQ, referenceJooq)));
    }

    @ParameterizedTest(name = "jOOQ {0} against {1} is a nudge")
    @CsvSource({
        // A minor line behind, the case the nudge exists for.
        "3.19.15, 3.20.11",
        // The lexical-compare trap: 3.9 sorts after 3.20 as a string and before it as a version.
        "3.9.6,   3.20.11",
        // Major-line lag falls out of the same (major, minor) compare with no special-casing.
        "2.6.4,   3.20.11",
        // A qualifier does not hide the lag; the release part is what the line is read from.
        "3.19.0-SNAPSHOT, 3.20.11",
        // A single-segment pin is read as minor 0, so it is compared rather than dropped.
        "2,       3.20.11",
    })
    void behindOnTheMinorLine_nudges(String observed, String reference) {
        assertThat(warnings(observed, reference))
            .singleElement()
            .isInstanceOfSatisfying(BuildWarning.LintFinding.class, lf -> {
                assertThat(lf.rule()).isEqualTo(LintRule.JOOQ_VERSION_LAG);
                assertThat(lf.location())
                    .as("a resolved dependency version is a whole-build fact with no SDL coordinate")
                    .isNull();
            });
    }

    @ParameterizedTest(name = "jOOQ {0} against {1} is silent")
    @CsvSource({
        // At the reference version.
        "3.20.11, 3.20.11",
        // Patch-level lag within the current minor is materially current; saying so every build is noise.
        "3.20.9,  3.20.11",
        // Ahead of the reference: the state every consumer enters while a graphitron upgrade is in
        // flight, and what an implementer writing != instead of < would get wrong.
        "3.21.0,  3.20.11",
        "4.0.1,   3.20.11",
        // A version that does not decompose into (major, minor) is silent, not a message.
        "RELEASE, 3.20.11",
        "3.x,     3.20.11",
        "'',      3.20.11",
        // Nor does an unreadable *reference* speak: graphitron cannot nudge toward a version it
        // failed to read off its own realm.
        "3.19.15, unknown",
    })
    void currentAheadOrUnreadable_isSilent(String observed, String reference) {
        assertThat(warnings(observed, reference)).isEmpty();
    }

    @Test
    void coordinateAbsentFromEitherSide_isSilent() {
        // A consumer who carries no jOOQ on the scopes the generated code compiles against is not
        // a lagging consumer, and neither is one whose reference version never arrived.
        assertThat(DependencyVersionWarnings.forVersions(new DependencyVersions(
            Map.of(), only(WatchedDependency.JOOQ, "3.20.11")))).isEmpty();
        assertThat(DependencyVersionWarnings.forVersions(new DependencyVersions(
            only(WatchedDependency.JOOQ, "3.19.15"), Map.of()))).isEmpty();
        assertThat(DependencyVersionWarnings.forVersions(DependencyVersions.empty())).isEmpty();
    }

    @Test
    void theMessageCarriesObservedCurrentCoordinateAndTheSuppressionId() {
        // Actionable in one read: what you have, what is current, what to bump, how to silence it.
        assertThat(warnings("3.19.15", "3.20.11"))
            .singleElement()
            .satisfies(w -> assertThat(w.message())
                .contains("3.19.15")
                .contains("3.20.11")
                .contains("org.jooq:jooq")
                .contains("jooq-version-lag"));
    }

    @Test
    void bothDependenciesNudgeIndependently() {
        var observed = new EnumMap<WatchedDependency, String>(WatchedDependency.class);
        observed.put(WatchedDependency.JOOQ, "3.19.15");
        observed.put(WatchedDependency.GRAPHQL_JAVA, "22.3");
        var reference = new EnumMap<WatchedDependency, String>(WatchedDependency.class);
        reference.put(WatchedDependency.JOOQ, "3.20.11");
        reference.put(WatchedDependency.GRAPHQL_JAVA, "25.0");

        assertThat(DependencyVersionWarnings.forVersions(new DependencyVersions(observed, reference)))
            .extracting(w -> ((BuildWarning.LintFinding) w).rule())
            .containsExactly(LintRule.GRAPHQL_JAVA_VERSION_LAG, LintRule.JOOQ_VERSION_LAG);

        // One lagging dependency does not drag the other into the report.
        reference.put(WatchedDependency.JOOQ, "3.19.15");
        assertThat(DependencyVersionWarnings.forVersions(new DependencyVersions(observed, reference)))
            .extracting(w -> ((BuildWarning.LintFinding) w).rule())
            .containsExactly(LintRule.GRAPHQL_JAVA_VERSION_LAG);
    }

    @Test
    void graphqlJavaTwoSegmentVersionsCompareOnTheMinor() {
        // graphql-java's shape is major.minor with no patch, so the minor is the whole of the lag.
        var observed = only(WatchedDependency.GRAPHQL_JAVA, "24.1");
        assertThat(DependencyVersionWarnings.forVersions(
            new DependencyVersions(observed, only(WatchedDependency.GRAPHQL_JAVA, "25.0"))))
            .as("24.1 is behind 25.0 even though 1 > 0").hasSize(1);
        assertThat(DependencyVersionWarnings.forVersions(
            new DependencyVersions(observed, only(WatchedDependency.GRAPHQL_JAVA, "24.0"))))
            .as("24.1 is ahead of 24.0").isEmpty();
    }
}
