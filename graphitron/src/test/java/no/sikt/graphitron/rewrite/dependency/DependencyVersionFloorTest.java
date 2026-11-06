package no.sikt.graphitron.rewrite.dependency;

import no.sikt.graphitron.model.config.DependencyVersions;
import no.sikt.graphitron.model.config.ObservedVersion;
import no.sikt.graphitron.model.config.WatchedDependency;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The jOOQ minimum: a build resolving a line below 3.20 is refused with its own coordinate and
 * version, the minimum, and what to change; 3.20 and later pass, and so does a build the refusal
 * cannot decide on.
 */
@UnitTier
class DependencyVersionFloorTest {

    private static DependencyVersions jooqAt(ObservedVersion... observed) {
        return new DependencyVersions(Map.of(WatchedDependency.JOOQ, List.of(observed)), Map.of());
    }

    @Test
    void jooq319IsRefusedNamingTheVersionTheMinimumAndWhatToChange() {
        assertThat(DependencyVersionFloor.refusal(jooqAt(new ObservedVersion("org.jooq.pro:jooq", "3.19.18"))))
            .contains("Graphitron's generated code needs org.jooq.pro:jooq 3.20 or later, and this build "
                + "resolves 3.19.18. Raise the version your build resolves for org.jooq.pro:jooq to 3.20 or "
                + "later (usually the jooq.version property in your pom), regenerate your jOOQ catalog with "
                + "that version's codegen, and run graphitron again.");
    }

    @ParameterizedTest(name = "jOOQ {0} passes")
    @ValueSource(strings = {"3.20.0", "3.20.11", "3.21.6", "4.0.0"})
    void jooq320AndLaterPass(String version) {
        assertThat(DependencyVersionFloor.refusal(jooqAt(new ObservedVersion("org.jooq:jooq", version)))).isEmpty();
    }

    @Test
    void theLowestOfSeveralEditionsDecides() {
        assertThat(DependencyVersionFloor.refusal(jooqAt(
            new ObservedVersion("org.jooq:jooq", "3.20.11"),
            new ObservedVersion("org.jooq.pro:jooq", "3.19.18"))))
            .hasValueSatisfying(message -> assertThat(message).contains("org.jooq.pro:jooq", "3.19.18"));
    }

    @Test
    void noObservationAndAnUnreadableVersionPass() {
        assertThat(DependencyVersionFloor.refusal(DependencyVersions.empty())).isEmpty();
        assertThat(DependencyVersionFloor.refusal(jooqAt(new ObservedVersion("org.jooq:jooq", "LATEST")))).isEmpty();
    }

    @Test
    void graphqlJavaHasNoMinimum() {
        assertThat(DependencyVersionFloor.refusal(new DependencyVersions(Map.of(WatchedDependency.GRAPHQL_JAVA,
            List.of(new ObservedVersion("com.graphql-java:graphql-java", "1.0"))), Map.of()))).isEmpty();
    }
}
