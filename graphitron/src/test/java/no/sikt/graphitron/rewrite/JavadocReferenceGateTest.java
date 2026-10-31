package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Positive-evidence guard for the reference gate wired in the root pom: javac's doclint
 * {@code reference} group, switched on for the main compile through the {@code graphitron.doclint}
 * property. The gate fails the build on a dangling {@code @link}/{@code @see} target, so a link
 * that names a live symbol is a build-enforced pin. But a gate can also pass <em>vacuously</em>: an
 * {@code activeByDefault} profile is silently deactivated whenever another profile
 * ({@code -Plocal-db}) is named, and any covered module that sets the property to
 * {@code -Xdoclint:none} opts itself out silently. That is the same vacuous-pass hazard
 * {@link RoadmapReferenceGuardTest}'s scanned-file floor guards against, so it is guarded the same
 * way here: the wiring must be live, bound in the main build, and not disabled by any covered
 * module.
 *
 * <p>Coverage is a subset of {@link RoadmapReferenceGuardTest}'s in-scope set. The comment guard is
 * a pure text scan, so it covers every module; the reference gate compiles references, so a module
 * whose sources are generated is structurally exempt (see {@link #EXEMPT_MODULES}) and opts out
 * with the property. {@code roadmap-tool} is out of scope for both and also opts out.
 */
@UnitTier
class JavadocReferenceGateTest {

    /**
     * Modules the gate actively covers: hand-authored javadoc in {@code src/main/java}. The gate
     * must run, and not be switched off, for each.
     */
    private static final List<String> GATE_COVERED_MODULES = List.of(
        "graphitron-model",
        "graphitron",
        "graphitron-jakarta-rest",
        "graphitron-mcp",
        "graphitron-lsp",
        "graphitron-maven-plugin",
        "graphitron-fixtures-codegen",
        "graphitron-sakila-service"
    );

    /**
     * Modules outside the gate, each opting out with {@code -Xdoclint:none}:
     * {@code graphitron-sakila-example} compiles generated sources as the release-17 backstop and the
     * gate audits hand-authored javadoc only; {@code roadmap-tool} is outside the in-scope module set
     * altogether. The exemption is pinned so it stays deliberate rather than drifting into a silent
     * skip.
     */
    private static final List<String> EXEMPT_MODULES = List.of(
        "graphitron-sakila-example",
        "roadmap-tool"
    );

    private static final String GATE_PROPERTY = "<graphitron.doclint>";

    @Test
    void gateIsBoundInTheMainBuildNotAProfile() throws IOException {
        String rootPom = Files.readString(locateRepoRoot().resolve("pom.xml"));
        assertThat(rootPom)
            .as("the reference gate must be the doclint reference group on the main compile")
            .contains("<graphitron.doclint>-Xdoclint:reference/protected</graphitron.doclint>")
            .contains("<arg>${graphitron.doclint}</arg>")
            .as("the javadoc execution the javac check replaced must not come back beside it")
            .doesNotContain("check-link-references");

        int executionIdx = rootPom.indexOf("<arg>${graphitron.doclint}</arg>");
        int profilesIdx = rootPom.indexOf("<profiles>");
        assertThat(executionIdx)
            .as("the gate must be bound in the main <build>, not a profile: an activeByDefault "
                + "profile is silently deactivated whenever -Plocal-db is named, so a profile-hosted "
                + "gate would never run and would pass vacuously")
            .isGreaterThan(0)
            .isLessThan(profilesIdx);
        assertThat(rootPom.substring(0, executionIdx))
            .as("the argument rides the default-compile execution, so the test compile, which the "
                + "gate never covered, is left alone")
            .contains("<id>default-compile</id>");
    }

    @Test
    void noCoveredModuleOptsOutOfTheGate() throws IOException {
        Path repoRoot = locateRepoRoot();
        for (String module : GATE_COVERED_MODULES) {
            Path pom = repoRoot.resolve(module).resolve("pom.xml");
            assertThat(pom).exists();
            assertThat(Files.readString(pom))
                .as("%s is covered by the reference gate but overrides graphitron.doclint; "
                    + "the gate would then pass vacuously for that module", module)
                .doesNotContain(GATE_PROPERTY);
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
                    + "explicitly with graphitron.doclint so the exemption stays deliberate", module)
                .contains(GATE_PROPERTY + "-Xdoclint:none</graphitron.doclint>");
        }
    }

    @Test
    void theModelGateLeavesTheGeneratedTreeAlone() throws IOException {
        assertThat(Files.readString(locateRepoRoot().resolve("graphitron-model/pom.xml")))
            .as("the model module compiles four hundred generated tables beside its hand-written "
                + "code; the gate must exclude the generated packages, and only those, since "
                + "no.sikt.graphitron.model is also the root of the hand-written ones")
            .contains("-Xdoclint/package:-no.sikt.graphitron.model,"
                + "-no.sikt.graphitron.model.tables,-no.sikt.graphitron.model.tables.records");
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
