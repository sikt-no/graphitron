package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: no transient roadmap-item citation survives in an in-scope module, in
 * either of two habitats the {@link RoadmapReferenceScanner} lexer separates.
 *
 * <ul>
 *   <li>{@link #noRoadmapReferencesInComments} scans comment / javadoc regions across every
 *       in-scope module's main and test sources.</li>
 *   <li>{@link #noRoadmapReferencesInGeneratorStringLiterals} scans string, character, and
 *       text-block literals across every in-scope module's <em>main</em> sources: the rejection
 *       messages, invariant-throw messages, and documentation text emitted into generated
 *       output that would render a rotting id or slug at a consumer with no {@code roadmap/}
 *       directory. Test-source string literals citing an item as provenance are a distinct
 *       habitat and are deliberately not scanned; they render to no consumer surface.</li>
 * </ul>
 *
 * <p>See the "Javadoc conventions" rule in {@code CLAUDE.md} for the authoring convention this
 * backs. In-scope is the generator, its runtime/support modules, and the fixtures and tooling
 * around them; the one deliberate exclusion is {@code roadmap-tool}, whose entire domain is
 * roadmap items, so an item id in its sources is a legitimate reference rather than a stale
 * citation.
 *
 * <p>The scanned-file count is asserted well above zero: this guard lives in one module but
 * reaches its siblings by walking up to the repository root, so a drifted root (a rename, a
 * moved module) would silently scan nothing and pass vacuously. The floor catches that.
 *
 * <p>When this guard fires, rewrite the offending citation to name a live symbol (a
 * {@code {@link}} in a comment, the class / method in prose in a message string), a published
 * docs page, or simply the fact; do not suppress the guard.
 */
@UnitTier
class RoadmapReferenceGuardTest {

    /** Module source roots to scan, relative to the repository root. {@code roadmap-tool} is excluded by design. */
    private static final List<String> IN_SCOPE_MODULES = List.of(
        "graphitron",
        "graphitron-javapoet",
        "graphitron-jakarta-rest",
        "graphitron-mcp",
        "graphitron-lsp",
        "graphitron-maven-plugin",
        "graphitron-fixtures-codegen",
        "graphitron-sakila-db",
        "graphitron-sakila-service",
        "graphitron-sakila-example"
    );

    /** A floor on scanned files: comfortably below the true count, high enough to catch a walk that reached nothing. */
    private static final int MIN_SCANNED_FILES = 500;

    /** The main-source-only floor for the string-literal scan; comfortably below the true main-source count. */
    private static final int MIN_SCANNED_MAIN_FILES = 200;

    @Test
    void noRoadmapReferencesInComments() throws IOException {
        Path repoRoot = locateRepoRoot();
        List<RoadmapReferenceScanner.Finding> findings = new ArrayList<>();
        int scanned = 0;
        for (String module : IN_SCOPE_MODULES) {
            for (String tree : List.of("src/main/java", "src/test/java")) {
                Path root = repoRoot.resolve(module).resolve(tree);
                if (!Files.isDirectory(root)) continue;
                findings.addAll(RoadmapReferenceScanner.scan(root));
                try (var paths = Files.walk(root)) {
                    scanned += (int) paths.filter(p -> p.toString().endsWith(".java")).count();
                }
            }
        }

        assertThat(scanned)
            .as("the guard reaches sibling modules by walking to the repository root; a scanned-file "
                + "count near zero means the root drifted and the guard would pass vacuously")
            .isGreaterThan(MIN_SCANNED_FILES);

        assertThat(findings)
            .as("transient roadmap-item citations (R<n> / roadmap/<slug>) must not appear in comment or "
                + "javadoc regions; reference a live symbol, a docs page, or the fact itself instead "
                + "(see CLAUDE.md \"Javadoc conventions\"). Offending sites:\n"
                + findings.stream().map(Object::toString).reduce((a, b) -> a + "\n" + b).orElse(""))
            .isEmpty();
    }

    @Test
    void noRoadmapReferencesInGeneratorStringLiterals() throws IOException {
        Path repoRoot = locateRepoRoot();
        List<RoadmapReferenceScanner.Finding> findings = new ArrayList<>();
        int scanned = 0;
        for (String module : IN_SCOPE_MODULES) {
            // Main sources only: string literals that render to a consumer surface (author-facing
            // rejection messages, invariant-throw messages, documentation emitted into generated
            // output). A test's @DisplayName or assertion description citing an item as provenance
            // renders to no such surface, so the test tree is out of this projection's scope.
            Path root = repoRoot.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(root)) continue;
            findings.addAll(RoadmapReferenceScanner.scanStrings(root));
            try (var paths = Files.walk(root)) {
                scanned += (int) paths.filter(p -> p.toString().endsWith(".java")).count();
            }
        }

        assertThat(scanned)
            .as("the guard reaches sibling modules by walking to the repository root; a scanned-file "
                + "count near zero means the root drifted and the guard would pass vacuously")
            .isGreaterThan(MIN_SCANNED_MAIN_FILES);

        assertThat(findings)
            .as("transient roadmap-item citations (R<n> / roadmap/<slug>) must not appear in string, "
                + "character, or text-block literals of generator main sources; these render onto author "
                + "log surfaces, invariant-throw messages, or generated-output documentation where the id "
                + "rots and the consumer has no roadmap/ directory. State the fact or name the live "
                + "class/method in prose instead. Offending sites:\n"
                + findings.stream().map(Object::toString).reduce((a, b) -> a + "\n" + b).orElse(""))
            .isEmpty();
    }

    /**
     * Walks up from the test working directory to the repository root, identified by the
     * {@code roadmap/workflow.adoc} anchor. Surefire runs from the module directory, so the
     * root is one or more parents up.
     */
    private static Path locateRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("roadmap/workflow.adoc"))) return p;
        }
        throw new IllegalStateException("Could not locate the repository root by walking up from " + cwd);
    }
}
