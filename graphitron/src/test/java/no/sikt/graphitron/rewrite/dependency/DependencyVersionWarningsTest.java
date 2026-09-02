package no.sikt.graphitron.rewrite.dependency;

import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.config.DependencyVersions;
import no.sikt.graphitron.model.config.ObservedVersion;
import no.sikt.graphitron.model.config.WatchedDependency;

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

    /** The coordinate a single-edition consumer resolves, so the common rows read as one fact. */
    private static String canonicalCoordinate(WatchedDependency dep) {
        return dep == WatchedDependency.JOOQ ? "org.jooq:jooq" : "com.graphql-java:graphql-java";
    }

    private static Map<WatchedDependency, List<ObservedVersion>> only(
        WatchedDependency dep, String version
    ) {
        var map = new EnumMap<WatchedDependency, List<ObservedVersion>>(WatchedDependency.class);
        map.put(dep, List.of(new ObservedVersion(canonicalCoordinate(dep), version)));
        return map;
    }

    /** jOOQ resolved at several coordinates at once, the shape a mixed-edition classpath produces. */
    private static Map<WatchedDependency, List<ObservedVersion>> jooqAt(ObservedVersion... observed) {
        var map = new EnumMap<WatchedDependency, List<ObservedVersion>>(WatchedDependency.class);
        map.put(WatchedDependency.JOOQ, List.of(observed));
        return map;
    }

    /** The reference side stays one bare version per dependency: graphitron's realm resolves one. */
    private static Map<WatchedDependency, String> reference(WatchedDependency dep, String version) {
        var map = new EnumMap<WatchedDependency, String>(WatchedDependency.class);
        map.put(dep, version);
        return map;
    }

    private static Map<WatchedDependency, String> referenceJooq(String version) {
        return reference(WatchedDependency.JOOQ, version);
    }

    private static List<BuildWarning> warnings(String observedJooq, String referenceJooq) {
        return DependencyVersionWarnings.forVersions(new DependencyVersions(
            only(WatchedDependency.JOOQ, observedJooq), referenceJooq(referenceJooq)));
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
            Map.of(), referenceJooq("3.20.11")))).isEmpty();
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
        var observed = new EnumMap<WatchedDependency, List<ObservedVersion>>(WatchedDependency.class);
        observed.put(WatchedDependency.JOOQ, List.of(new ObservedVersion("org.jooq:jooq", "3.19.15")));
        observed.put(WatchedDependency.GRAPHQL_JAVA,
            List.of(new ObservedVersion("com.graphql-java:graphql-java", "22.3")));
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
        assertThat(DependencyVersionWarnings.forVersions(new DependencyVersions(
            observed, reference(WatchedDependency.GRAPHQL_JAVA, "25.0"))))
            .as("24.1 is behind 25.0 even though 1 > 0").hasSize(1);
        assertThat(DependencyVersionWarnings.forVersions(new DependencyVersions(
            observed, reference(WatchedDependency.GRAPHQL_JAVA, "24.0"))))
            .as("24.1 is ahead of 24.0").isEmpty();
    }

    // ---- Editions: one library, several coordinates ----

    @Test
    void theMessageNamesTheCommercialCoordinateTheConsumerResolved() {
        // The case this exists for. A Pro consumer told to bump the open-source coordinate is being
        // told to switch editions, which for an Oracle or SQL Server subgraph does not work at all.
        var versions = new DependencyVersions(
            jooqAt(new ObservedVersion("org.jooq.pro:jooq", "3.19.15")), referenceJooq("3.20.11"));

        assertThat(DependencyVersionWarnings.forVersions(versions))
            .singleElement()
            .satisfies(w -> assertThat(w.message())
                .contains("org.jooq.pro:jooq")
                .doesNotContain("org.jooq:jooq"));
    }

    @Test
    void theLowestObservedLineIsAdvisedOnWhicheverOrderItArrivesIn() {
        // A transitive open-source jOOQ alongside a direct Pro one. The lowest line is the one holding
        // the consumer back, and the answer cannot depend on artifact-set iteration order.
        var openSource = new ObservedVersion("org.jooq:jooq", "3.15.4");
        var pro = new ObservedVersion("org.jooq.pro:jooq", "3.19.15");

        for (List<ObservedVersion> order : List.of(List.of(openSource, pro), List.of(pro, openSource))) {
            assertThat(DependencyVersionWarnings.lowestLine(order))
                .as("the 3.15 line is lowest, whichever order it arrives in")
                .isEqualTo(openSource);
        }
    }

    @Test
    void theLowestLineWinsEvenWhenItIsTheCommercialOne() {
        // Guards the selection against collapsing into "prefer the open-source coordinate", which the
        // previous case alone would not catch.
        var pro = new ObservedVersion("org.jooq.pro:jooq", "3.15.4");
        var openSource = new ObservedVersion("org.jooq:jooq", "3.19.15");

        assertThat(DependencyVersionWarnings.forVersions(
            new DependencyVersions(jooqAt(openSource, pro), referenceJooq("3.20.11"))))
            .singleElement()
            .satisfies(w -> assertThat(w.message()).contains("org.jooq.pro:jooq").contains("3.15.4"));
    }

    @Test
    void twoEditionsOnOneLineTieBreakOnTheCoordinateString() {
        // Both on 3.19, so the line does not separate them and the message would otherwise move
        // between runs on an unchanged project. Lowest coordinate string decides, which puts
        // `org.jooq.pro:jooq` first because '.' sorts below ':'.
        var pro = new ObservedVersion("org.jooq.pro:jooq", "3.19.15");
        var openSource = new ObservedVersion("org.jooq:jooq", "3.19.1");

        for (List<ObservedVersion> order : List.of(List.of(pro, openSource), List.of(openSource, pro))) {
            assertThat(DependencyVersionWarnings.lowestLine(order))
                .as("a tie resolves the same way in both input orders")
                .isEqualTo(pro);
        }
    }

    @Test
    void oneAdvisoryPerLibraryNoMatterHowManyEditionsLag() {
        // A mixed-edition classpath is its own problem and not this advisory's to report.
        var versions = new DependencyVersions(
            jooqAt(new ObservedVersion("org.jooq:jooq", "3.15.4"),
                   new ObservedVersion("org.jooq.pro:jooq", "3.16.2"),
                   new ObservedVersion("org.jooq.pro-java-11:jooq", "3.17.8")),
            referenceJooq("3.20.11"));

        assertThat(DependencyVersionWarnings.forVersions(versions)).hasSize(1);
    }

    @Test
    void anUnreadableObservationDoesNotSpeakForTheReadableOnes() {
        // An unreadable version cannot be ordered, but the readable observations are still true, so it
        // is passed over rather than allowed to silence them.
        assertThat(DependencyVersionWarnings.forVersions(new DependencyVersions(
            jooqAt(new ObservedVersion("org.jooq.pro:jooq", "RELEASE"),
                   new ObservedVersion("org.jooq:jooq", "3.19.15")),
            referenceJooq("3.20.11"))))
            .singleElement()
            .satisfies(w -> assertThat(w.message()).contains("org.jooq:jooq").contains("3.19.15"));

        assertThat(DependencyVersionWarnings.forVersions(new DependencyVersions(
            jooqAt(new ObservedVersion("org.jooq.pro:jooq", "RELEASE")), referenceJooq("3.20.11"))))
            .as("nothing readable to order is the silence case, not a message")
            .isEmpty();
    }
}
