package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the pom half of the scoped-build claim: a build step over {@code roadmap/} in a pom outside
 * the two roadmap-reading modules must fail, while the shapes the reactor already has must pass.
 * The two that matter are {@code <module>roadmap-tool</module>}, which the path-shaped rule must
 * not take as a substring hit, and roadmap paths inside XML comments, which the root pom has
 * several of.
 */
class RoadmapConsumerPomCheckTest {

    @Test
    void aBuildStepOverTheRoadmapDirectoryIsReported() {
        String pom = "<project><build><plugins><plugin><configuration>"
            + "<commandlineArgs>render-adoc ../roadmap target/staging</commandlineArgs>"
            + "</configuration></plugin></plugins></build></project>";

        assertThat(RoadmapConsumerPomCheck.checkPom("graphitron-mcp", pom))
            .singleElement().asString()
            .contains("graphitron-mcp")
            .contains("roadmap");
    }

    @Test
    void theBareDirectoryIsReported() {
        assertThat(RoadmapConsumerPomCheck.checkPom("m",
            "<project><properties><scanDir>roadmap</scanDir></properties></project>"))
            .hasSize(1);
    }

    @Test
    void theModuleNameIsNotAPath() {
        assertThat(RoadmapConsumerPomCheck.checkPom("(root pom)",
            "<project><modules><module>roadmap-tool</module><module>docs</module></modules></project>"))
            .isEmpty();
    }

    @Test
    void aCommentedReferenceNeverTripsTheScan() {
        assertThat(RoadmapConsumerPomCheck.checkPom("(root pom)",
            "<project><!-- aggregates into roadmap/source-coverage.adoc --></project>"))
            .isEmpty();
    }

    @Test
    void aMultiLineCommentedReferenceNeverTripsTheScan() {
        assertThat(RoadmapConsumerPomCheck.checkPom("(root pom)",
            "<project>\n<!-- the only consumer is the manual regen\n"
                + "     documented in roadmap/inference-axis-coverage.adoc -->\n</project>"))
            .isEmpty();
    }

    @Test
    void aPomWithNoRoadmapReferenceAtAllPasses() {
        assertThat(RoadmapConsumerPomCheck.checkPom("m", "<project></project>")).isEmpty();
    }

    @Test
    void theTwoRoadmapReadingModulesAreTheAllowedSet() {
        assertThat(RoadmapConsumerPomCheck.ROADMAP_READING_MODULES)
            .as("the allowed set is the scoped verification build's module selector; changing one "
                + "without the other would leave CLAUDE.md describing a different build")
            .containsExactlyInAnyOrder("roadmap-tool", "docs");
    }

}
