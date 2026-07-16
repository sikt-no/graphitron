package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Positive-evidence guard for the javadoc reference gate wired in the root pom (the
 * maven-javadoc-plugin {@code javadoc} goal with doclint {@code reference}). The gate fails the
 * build on a dangling {@code @link}/{@code @see} target, so a link that names a live symbol is a
 * build-enforced pin. But a javadoc gate can also pass <em>vacuously</em>: an {@code activeByDefault}
 * profile is silently deactivated whenever another profile ({@code -Plocal-db}) is named, and any
 * covered module that sets {@code maven.javadoc.skip} opts itself out silently. That is the same
 * vacuous-pass hazard {@link RoadmapReferenceGuardTest}'s scanned-file floor guards against, so it
 * is guarded the same way here: the wiring must be live, bound in the main build, and not disabled
 * by any covered module.
 *
 * <p>Coverage is a subset of {@link RoadmapReferenceGuardTest}'s in-scope set. The comment guard is
 * a pure text scan, so it covers every module; the javadoc gate compiles references, so two modules
 * are structurally exempt (see {@link #EXEMPT_MODULES}) and opt out with {@code maven.javadoc.skip}.
 * {@code roadmap-tool} is out of scope for both and also opts out.
 */
@UnitTier
class JavadocReferenceGateTest {

    /**
     * Modules the gate actively covers: hand-authored javadoc that resolves on the
     * {@code src/main/java} sourcepath. The gate must run, and not be skipped, for each.
     */
    private static final List<String> GATE_COVERED_MODULES = List.of(
        "graphitron",
        "graphitron-javapoet",
        "graphitron-jakarta-rest",
        "graphitron-mcp",
        "graphitron-lsp",
        "graphitron-maven-plugin",
        "graphitron-fixtures-codegen",
        "graphitron-sakila-service"
    );

    /**
     * In the comment guard's set but structurally exempt from the javadoc gate, each opting out with
     * {@code maven.javadoc.skip}: {@code graphitron-sakila-db} is jOOQ-generated only (no
     * hand-authored main sources); {@code graphitron-sakila-example}'s hand-authored app imports
     * generated types not on the {@code src/main/java} sourcepath, so javadoc cannot resolve them.
     * The exemption is pinned so it stays deliberate rather than drifting into a silent skip.
     */
    private static final List<String> EXEMPT_MODULES = List.of(
        "graphitron-sakila-example"
    );

    @Test
    void gateIsBoundInTheMainBuildNotAProfile() throws IOException {
        String rootPom = Files.readString(locateRepoRoot().resolve("pom.xml"));
        assertThat(rootPom)
            .as("the reference gate execution must exist in the root pom, configured for the "
                + "doclint reference group")
            .contains("check-link-references")
            .contains("<doclint>reference</doclint>");

        int executionIdx = rootPom.indexOf("check-link-references");
        int profilesIdx = rootPom.indexOf("<profiles>");
        assertThat(executionIdx)
            .as("the gate must be bound in the main <build>, not a profile: an activeByDefault "
                + "profile is silently deactivated whenever -Plocal-db is named, so a profile-hosted "
                + "gate would never run and would pass vacuously")
            .isGreaterThan(0)
            .isLessThan(profilesIdx);
    }

    @Test
    void noCoveredModuleOptsOutOfTheGate() throws IOException {
        Path repoRoot = locateRepoRoot();
        for (String module : GATE_COVERED_MODULES) {
            Path pom = repoRoot.resolve(module).resolve("pom.xml");
            assertThat(pom).exists();
            assertThat(Files.readString(pom))
                .as("%s is covered by the reference gate but disables it with maven.javadoc.skip; "
                    + "the gate would then pass vacuously for that module", module)
                .doesNotContain("<maven.javadoc.skip>true</maven.javadoc.skip>");
        }
    }

    @Test
    void coveredModulesHaveHandAuthoredMainSources() throws IOException {
        Path repoRoot = locateRepoRoot();
        for (String module : GATE_COVERED_MODULES) {
            Path src = repoRoot.resolve(module).resolve("src/main/java");
            assertThat(Files.isDirectory(src))
                .as("%s should have a src/main/java for the gate to scan", module)
                .isTrue();
            try (var paths = Files.walk(src)) {
                long javaFiles = paths.filter(p -> p.toString().endsWith(".java")).count();
                assertThat(javaFiles)
                    .as("%s has no .java under src/main/java, so the gate would scan nothing there", module)
                    .isGreaterThan(0);
            }
        }
    }

    @Test
    void exemptModulesOptOutDeliberately() throws IOException {
        Path repoRoot = locateRepoRoot();
        for (String module : EXEMPT_MODULES) {
            Path pom = repoRoot.resolve(module).resolve("pom.xml");
            assertThat(pom).exists();
            assertThat(Files.readString(pom))
                .as("%s is exempt from the reference gate for a structural reason and must opt out "
                    + "explicitly with maven.javadoc.skip so the exemption stays deliberate", module)
                .contains("<maven.javadoc.skip>true</maven.javadoc.skip>");
        }
    }

    /**
     * Walks up from the test working directory to the repository root, identified by the
     * {@code roadmap/workflow.adoc} anchor. Surefire runs from the module directory, so the root is
     * one or more parents up.
     */
    private static Path locateRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("roadmap/workflow.adoc"))) return p;
        }
        throw new IllegalStateException("Could not locate the repository root by walking up from " + cwd);
    }
}
