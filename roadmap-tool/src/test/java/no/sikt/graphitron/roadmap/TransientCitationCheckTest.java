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
 * Pins the transient-citation contract: an item id or a {@code roadmap/<slug>} path in
 * agent-onboarding prose is a finding, the three permanent artifacts and the rule's own
 * placeholder forms are not, and a declared document that has gone missing fails the run
 * instead of shrinking the scan to nothing.
 */
class TransientCitationCheckTest {

    /** Writes both declared documents under {@code root}, so a scan reaches its full habitat. */
    private static void writeBothDocs(Path root, String claudeMd, String webEnvMd) throws IOException {
        Files.writeString(root.resolve("CLAUDE.md"), claudeMd);
        Path claudeDir = root.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("web-environment.md"), webEnvMd);
    }

    @Test
    void itemId_isFlagged(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("CLAUDE.md");
        Files.writeString(file, """
            # Reference

            The dev goal walks the parent pom's modules. See R99 for the rationale.
            """);

        List<TransientCitationCheck.Finding> findings = TransientCitationCheck.scanFile(file);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).line()).isEqualTo(3);
        assertThat(findings.get(0).citation()).isEqualTo("R99");
    }

    @Test
    void itemSlugPath_isFlagged(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("CLAUDE.md");
        Files.writeString(file, "See `roadmap/lsp-submodule-sibling-classpath.md` for the rationale.\n");

        List<TransientCitationCheck.Finding> findings = TransientCitationCheck.scanFile(file);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).citation()).isEqualTo("roadmap/lsp-submodule-sibling-classpath.md");
    }

    @Test
    void permanentArtifactPaths_areNotFlagged(@TempDir Path dir) throws IOException {
        // The three artifacts outlive every item, so prose may point at them by path.
        Path file = dir.resolve("CLAUDE.md");
        Files.writeString(file, """
            Consult `roadmap/workflow.adoc` for the state table, `roadmap/README.md` for the
            roll-up, and `roadmap/changelog.md` for what has shipped.
            """);

        assertThat(TransientCitationCheck.scanFile(file)).isEmpty();
    }

    @Test
    void ruleStatingItsOwnForbiddenShapes_isNotFlagged(@TempDir Path dir) throws IOException {
        // CLAUDE.md states the rule using the placeholder forms `R<n>` and `roadmap/<slug>`, and a
        // guard that fired on the rule's own wording could never be satisfied. A bare `roadmap/`
        // directory mention is likewise not a citation.
        Path file = dir.resolve("CLAUDE.md");
        Files.writeString(file, """
            A roadmap item id (`R<n>`) or a `roadmap/<slug>` path is transient. Many sessions
            edit `roadmap/` concurrently.
            """);

        assertThat(TransientCitationCheck.scanFile(file)).isEmpty();
    }

    @Test
    void roadmapToolIdentifier_isNotFlagged(@TempDir Path dir) throws IOException {
        // The module identifier shares the `roadmap` prefix but has no path separator after it.
        Path file = dir.resolve("CLAUDE.md");
        Files.writeString(file, "Regenerate with `mvn -pl roadmap-tool exec:java -q`.\n");

        assertThat(TransientCitationCheck.scanFile(file)).isEmpty();
    }

    @Test
    void scan_readsEveryDeclaredDocument(@TempDir Path dir) throws IOException {
        writeBothDocs(dir, "# Clean\n\nNo citations.\n", "# Web env\n\nThe hook warms the build (R439).\n");

        TransientCitationCheck.Result result = TransientCitationCheck.scan(dir);

        assertThat(result.missing()).isEmpty();
        assertThat(result.scanned()).isEqualTo(TransientCitationCheck.SCANNED_DOCS.size());
        assertThat(result.findings()).singleElement()
            .satisfies(f -> {
                assertThat(f.doc()).isEqualTo(".claude/web-environment.md");
                assertThat(f.citation()).isEqualTo("R439");
            });
    }

    @Test
    void scan_reportsDeclaredDocumentThatIsAbsent(@TempDir Path dir) throws IOException {
        // The floor against a vacuous pass: if a declared document moved or was renamed, the scan
        // must say so rather than quietly reading one fewer file and reporting all clear.
        Files.writeString(dir.resolve("CLAUDE.md"), "# Clean\n\nNo citations.\n");

        TransientCitationCheck.Result result = TransientCitationCheck.scan(dir);

        assertThat(result.findings()).isEmpty();
        assertThat(result.missing()).containsExactly(".claude/web-environment.md");
    }

    @Test
    void run_withFindings_throwsBuildFailure(@TempDir Path dir) throws IOException {
        writeBothDocs(dir, "See R99 for why.\n", "# Web env\n\nClean.\n");

        assertThatThrownBy(() -> TransientCitationCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_withMissingDocument_throwsBuildFailure(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("CLAUDE.md"), "# Clean\n\nNo citations.\n");

        assertThatThrownBy(() -> TransientCitationCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void run_clean_returnsZero(@TempDir Path dir) throws IOException {
        writeBothDocs(dir, "# Clean\n\nSee `roadmap/workflow.adoc`.\n", "# Web env\n\nClean.\n");

        assertThat(TransientCitationCheck.run(List.of(dir.toString()))).isZero();
    }

    @Test
    void run_usageError_returnsExitCodeWithoutThrowing() throws IOException {
        // Argument errors are CLI dev errors, not a verify-phase tripwire, so they keep returning
        // the conventional 64 (EX_USAGE) for the dispatcher to System.exit on.
        assertThat(TransientCitationCheck.run(List.of())).isEqualTo(64);
    }

    @Test
    void run_againstThisRepository_isClean() throws IOException {
        // The check is only worth its keep if the tree it guards satisfies it, and this is the
        // assertion that keeps the two declared documents honest from the test tier as well as
        // from the verify phase.
        assertThat(TransientCitationCheck.run(List.of(repoRoot().toString()))).isZero();
    }

    /** Walks up from the module basedir to the reactor root that holds the scanned documents. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("CLAUDE.md"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("reactor root holding CLAUDE.md").isNotNull();
        return dir;
    }
}
