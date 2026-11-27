package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the agent-wiring contract: an {@code <argLine>} that does not lead with
 * {@code @{argLine}} drops the JaCoCo agent and must fail with the false-0% consequence in the
 * message; a second test-executing execution without a distinct {@code destFile} must fail; an
 * agent execution without the {@code no.sikt.*} include, or a class outside {@code no.sikt},
 * must fail; the compliant shapes of every rule pass; and commented-out XML never trips the scan.
 */
class CoverageAgentWiringCheckTest {

    @Test
    void compliantArgLine_passes() {
        String pom = surefire("<argLine>@{argLine} --enable-native-access=ALL-UNNAMED</argLine>");

        assertThat(CoverageAgentWiringCheck.checkPom("m", pom)).isEmpty();
    }

    @Test
    void bareArgLine_failsNamingModuleAndConsequence() {
        String pom = surefire("<argLine>--enable-native-access=ALL-UNNAMED</argLine>");

        assertThat(CoverageAgentWiringCheck.checkPom("graphitron-lsp", pom))
            .singleElement().asString()
            .contains("graphitron-lsp")
            .contains("@{argLine}")
            .contains("false 0%");
    }

    @Test
    void noSurefireConfigAtAll_passes() {
        assertThat(CoverageAgentWiringCheck.checkPom("m", "<project></project>")).isEmpty();
    }

    @Test
    void selfClosedArgLineProperty_passes() {
        // The root pom declares an empty <argLine/> property so surefire's late @{argLine}
        // replacement always has a value in scope; an empty element is not an override.
        assertThat(CoverageAgentWiringCheck.checkPom("m",
            "<project><properties><argLine/></properties></project>")).isEmpty();
    }

    @Test
    void commentedOutArgLine_doesNotTrip() {
        String pom = "<project><!-- <argLine>-Xmx1g</argLine> --></project>";

        assertThat(CoverageAgentWiringCheck.checkPom("m", pom)).isEmpty();
    }

    @Test
    void failsafeExecutionWithoutDistinctDestFile_fails() {
        String pom = plugin("maven-failsafe-plugin",
            "<executions><execution><goals><goal>integration-test</goal></goals></execution></executions>");

        assertThat(CoverageAgentWiringCheck.checkPom("m", pom))
            .singleElement().asString()
            .contains("maven-failsafe-plugin")
            .contains("destFile");
    }

    @Test
    void failsafeExecutionWithDistinctDestFile_passes() {
        String pom = "<project><build><plugins>"
            + "<plugin><artifactId>maven-failsafe-plugin</artifactId>"
            + "<executions><execution><goals><goal>integration-test</goal></goals></execution></executions></plugin>"
            + "<plugin><artifactId>jacoco-maven-plugin</artifactId><executions><execution>"
            + "<id>prepare-agent-integration</id><goals><goal>prepare-agent-integration</goal></goals>"
            + "<configuration><destFile>${project.build.directory}/jacoco-it.exec</destFile>"
            + "<includes><include>no.sikt.*</include></includes></configuration>"
            + "</execution></executions></plugin>"
            + "</plugins></build></project>";

        assertThat(CoverageAgentWiringCheck.checkPom("m", pom)).isEmpty();
    }

    @Test
    void failsafeWithoutExecutions_passes() {
        // Configuration-only failsafe (as in the root pom's pluginManagement and the
        // leaf-coverage profile) binds nothing, so it is not a second test-executing execution.
        String pom = plugin("maven-failsafe-plugin",
            "<configuration><redirectTestOutputToFile>true</redirectTestOutputToFile></configuration>");

        assertThat(CoverageAgentWiringCheck.checkPom("m", pom)).isEmpty();
    }

    @Test
    void forkCountOtherThanOne_fails() {
        // No distinct-destFile escape for forkCount: parallel forks share one destFile and
        // append=false makes them truncate each other, and forkCount=0 skips the agent entirely.
        for (String bad : List.of("2", "1C", "0")) {
            assertThat(CoverageAgentWiringCheck.checkPom("m",
                surefire("<forkCount>" + bad + "</forkCount>")))
                .singleElement().asString().contains("<forkCount>" + bad + "</forkCount>");
        }
    }

    @Test
    void forkCountOfExactlyOne_passes() {
        assertThat(CoverageAgentWiringCheck.checkPom("m", surefire("<forkCount>1</forkCount>")))
            .isEmpty();
    }

    @Test
    void prepareAgentWithoutInclude_failsNamingTheInclude() {
        String pom = plugin("jacoco-maven-plugin", "<executions><execution><id>prepare-agent</id>"
            + "<goals><goal>prepare-agent</goal></goals>"
            + "<configuration><append>false</append></configuration></execution></executions>");

        assertThat(CoverageAgentWiringCheck.checkPom("m", pom))
            .singleElement().asString()
            .contains("prepare-agent")
            .contains("<include>no.sikt.*</include>");
    }

    @Test
    void everyPrepareAgentExecutionCarriesTheInclude() {
        // Execution configuration is not shared, so a second agent execution without the
        // include instruments everything for its fork even when the first is restricted.
        String pom = plugin("jacoco-maven-plugin", "<executions>"
            + "<execution><goals><goal>prepare-agent</goal></goals>"
            + "<configuration><includes><include>no.sikt.*</include></includes></configuration></execution>"
            + "<execution><goals><goal>prepare-agent-integration</goal></goals>"
            + "<configuration><destFile>it.exec</destFile></configuration></execution>"
            + "<execution><goals><goal>report</goal></goals></execution>"
            + "</executions>");

        assertThat(CoverageAgentWiringCheck.checkPom("m", pom)).singleElement().asString()
            .contains("prepare-agent");
    }

    @Test
    void generatedPackageOutsideTheRoot_fails() {
        String pom = plugin("graphitron-maven-plugin",
            "<configuration><outputPackage>org.example.generated</outputPackage>"
            + "<jooqPackage>${jooqPackage}</jooqPackage></configuration>");

        assertThat(CoverageAgentWiringCheck.checkPom("m", pom))
            .singleElement().asString()
            .contains("org.example.generated")
            .contains("uncovered");
    }

    @Test
    void generatedPackageUnderTheRoot_passes() {
        String pom = plugin("jooq-codegen-maven",
            "<configuration><packageName>no.sikt.graphitron.model</packageName></configuration>");

        assertThat(CoverageAgentWiringCheck.checkPom("m", pom)).isEmpty();
    }

    @Test
    void sourcesOutsideTheRoot_fail(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("no/sikt/a"));
        Files.writeString(dir.resolve("no/sikt/a/A.java"), "package no.sikt.a; class A {}");
        Files.createDirectories(dir.resolve("org/example"));
        Files.writeString(dir.resolve("org/example/B.java"), "package org.example; class B {}");
        Files.writeString(dir.resolve("C.java"), "class C {}");

        assertThat(CoverageAgentWiringCheck.checkSources("m", dir))
            .hasSize(2)
            .anySatisfy(p -> assertThat(p).contains("'org.example'"))
            .anySatisfy(p -> assertThat(p).contains("''"));
    }

    @Test
    void sourcesUnderTheRootOrAbsent_pass(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("no/sikt/a"));
        Files.writeString(dir.resolve("no/sikt/a/A.java"), "package no.sikt.a; class A {}");

        assertThat(CoverageAgentWiringCheck.checkSources("m", dir)).isEmpty();
        assertThat(CoverageAgentWiringCheck.checkSources("m", dir.resolve("absent"))).isEmpty();
    }

    @Test
    void run_withBrokenModule_throwsBuildFailure(@TempDir Path dir) throws IOException {
        writeTree(dir, surefire("<argLine>-Xmx1g</argLine>"));

        assertThatThrownBy(() -> CoverageAgentWiringCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_withDeclaredModuleMissingItsPom_throwsBuildFailure(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"),
            "<project><modules><module>missing</module></modules></project>");

        assertThatThrownBy(() -> CoverageAgentWiringCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_clean_returnsZero(@TempDir Path dir) throws IOException {
        writeTree(dir, surefire("<argLine>@{argLine} -Xmx1g</argLine>"));

        assertThat(CoverageAgentWiringCheck.run(List.of(dir.toString()))).isZero();
    }

    @Test
    void run_usageError_returnsExitCodeWithoutThrowing() throws IOException {
        assertThat(CoverageAgentWiringCheck.run(List.of())).isEqualTo(64);
    }

    @Test
    void run_againstThisRepository_isClean() throws IOException {
        // The reactor's own poms must satisfy the check they are guarded by; this is the
        // assertion that the three @{argLine} prepends and the failsafe non-binding hold.
        assertThat(CoverageAgentWiringCheck.run(List.of(repoRoot().toString()))).isZero();
    }

    private static String surefire(String config) {
        return plugin("maven-surefire-plugin", "<configuration>" + config + "</configuration>");
    }

    private static String plugin(String artifactId, String body) {
        return "<project><build><plugins><plugin><artifactId>" + artifactId + "</artifactId>"
            + body + "</plugin></plugins></build></project>";
    }

    /** A root pom declaring one module, whose pom is {@code modulePom}. */
    private static void writeTree(Path root, String modulePom) throws IOException {
        Files.writeString(root.resolve("pom.xml"),
            "<project><modules><module>m</module></modules></project>");
        Files.createDirectories(root.resolve("m"));
        Files.writeString(root.resolve("m/pom.xml"), modulePom);
    }

    /** Walks up from the module basedir to the reactor root. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("CLAUDE.md"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("reactor root holding CLAUDE.md").isNotNull();
        return dir;
    }
}
