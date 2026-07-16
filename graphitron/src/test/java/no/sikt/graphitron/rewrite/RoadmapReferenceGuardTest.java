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
 * Structural guard: no transient roadmap-item citation survives in a comment or javadoc
 * region of an in-scope module. See the "Javadoc conventions" rule in {@code CLAUDE.md} for
 * the authoring convention this backs, and {@link RoadmapReferenceScanner} for the lexer that
 * separates comment regions from string literals.
 *
 * <p>In-scope is the generator, its runtime/support modules, and the fixtures and tooling
 * around them; the one deliberate exclusion is {@code roadmap-tool}, whose entire domain is
 * roadmap items, so an item id in its sources is a legitimate reference rather than a stale
 * documentation citation.
 *
 * <p>The scanned-file count is asserted well above zero: this guard lives in one module but
 * reaches its siblings by walking up to the repository root, so a drifted root (a rename, a
 * moved module) would silently scan nothing and pass vacuously. The floor catches that.
 *
 * <p>When this guard fires, rewrite the offending comment to name a live symbol (a
 * {@code {@link}}), a published docs page, or simply the fact; do not suppress the guard.
 * A roadmap id inside a user-facing message string is a different concern in a habitat this
 * guard does not scan, so it is not what trips here.
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
