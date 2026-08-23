package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the transient-citation contract: an item id or a {@code roadmap/<slug>} path in
 * agent-onboarding prose is a finding, the three permanent artifacts and the rule's own
 * placeholder forms are not, and a declared document that has gone missing fails the run
 * instead of shrinking the scan to nothing.
 */
class TransientCitationCheckTest {

    /**
     * Writes both declared documents under {@code root}, plus one clean page in every declared
     * tree, so a scan reaches its full habitat. A tree left empty makes the walk report it
     * missing, which is the anti-vacuous floor doing its job rather than a fixture problem.
     */
    private static void writeBothDocs(Path root, String claudeMd, String webEnvMd) throws IOException {
        writeOnlyTheDocs(root, claudeMd, webEnvMd);
        for (String tree : TransientCitationCheck.SCANNED_TREES) {
            writeTreePage(root, tree, "clean.adoc", "= Clean\n\nNo citations here.\n");
        }
    }

    /** The two declared documents and no trees, for the cases that are about a missing habitat. */
    private static void writeOnlyTheDocs(Path root, String claudeMd, String webEnvMd) throws IOException {
        Files.writeString(root.resolve("CLAUDE.md"), claudeMd);
        Path claudeDir = root.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("web-environment.md"), webEnvMd);
    }

    /** Writes one page into the named declared tree under {@code root}. */
    private static Path writeTreePage(Path root, String tree, String name, String content) throws IOException {
        Path dir = root.resolve(tree);
        Files.createDirectories(dir);
        Path page = dir.resolve(name);
        Files.writeString(page, content);
        return page;
    }

    /** The first declared tree, for a case that only needs some page in some scanned tree. */
    private static String firstTree() {
        return TransientCitationCheck.SCANNED_TREES.get(0);
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
        assertThat(result.scanned())
            .as("both declared documents plus one page in each walked tree")
            .isEqualTo(TransientCitationCheck.SCANNED_DOCS.size()
                + TransientCitationCheck.SCANNED_TREES.size());
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
        for (String tree : TransientCitationCheck.SCANNED_TREES) {
            writeTreePage(dir, tree, "clean.adoc", "= Clean\n\nNo citations here.\n");
        }

        TransientCitationCheck.Result result = TransientCitationCheck.scan(dir);

        assertThat(result.findings()).isEmpty();
        assertThat(result.missing()).containsExactly(".claude/web-environment.md");
    }

    @Test
    void itemIdOnAnArchitecturePage_isFlagged(@TempDir Path dir) throws IOException {
        // The habitat this walk exists for: the same rule, in a tree the fixed list never reached.
        writeBothDocs(dir, "# Clean\n\nNo citations.\n", "# Web env\n\nClean.\n");
        writeTreePage(dir, firstTree(), "rotting.adoc", "= Page\n\nUPSERT generation is gated pending R145.\n");

        TransientCitationCheck.Result result = TransientCitationCheck.scan(dir);

        assertThat(result.missing()).isEmpty();
        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.doc()).isEqualTo(firstTree() + "/rotting.adoc");
            assertThat(f.citation()).isEqualTo("R145");
        });
    }

    @Test
    void changelogPathOnAnArchitecturePage_isNotFlagged(@TempDir Path dir) throws IOException {
        // The changelog is a permanent artifact, and it is the redirect the rule points provenance
        // at, so a page citing it must stay clean or the rule has nowhere to send an author.
        writeBothDocs(dir, "# Clean\n\nNo citations.\n", "# Web env\n\nClean.\n");
        writeTreePage(dir, firstTree(), "cites.adoc", "= Page\n\nWhat shipped is in `roadmap/changelog.md`.\n");

        assertThat(TransientCitationCheck.scan(dir).findings()).isEmpty();
    }

    @Test
    void renderedRoadmapIndexXref_isNotFlagged(@TempDir Path dir) throws IOException {
        // roadmap/index.adoc is how the generated roll-up renders into the site, so it is the same
        // permanent artifact as roadmap/README.md under its published name, and the site's own
        // navigation link to it must not read as a transient citation.
        writeBothDocs(dir, "# Clean\n\nNo citations.\n", "# Web env\n\nClean.\n");
        writeTreePage(dir, firstTree(), "entry.adoc", "= Page\n\nSee xref:../roadmap/index.adoc[the Rewrite Roadmap].\n");

        assertThat(TransientCitationCheck.scan(dir).findings()).isEmpty();
    }

    @Test
    void aWalkedTreeThatReachesNoPages_isReportedMissing(@TempDir Path dir) throws IOException {
        // The anti-vacuous floor, one level up from the fixed list's: a renamed docs directory
        // must fail the check rather than silently scanning nothing and reporting all clear.
        writeOnlyTheDocs(dir, "# Clean\n\nNo citations.\n", "# Web env\n\nClean.\n");

        TransientCitationCheck.Result result = TransientCitationCheck.scan(dir);

        assertThat(result.missing())
            .as("every declared tree that reached no page must be named, not just the first")
            .hasSize(TransientCitationCheck.SCANNED_TREES.size());
        assertThatThrownBy(() -> TransientCitationCheck.run(List.of(dir.toString())))
            .isInstanceOf(BuildFailure.class);
    }

    @Test
    void aBaselineEntryWhoseCitationIsGone_isStale() throws IOException {
        // The property that keeps the burn-down list from becoming a suppression list: an entry
        // for a citation nobody writes any more must be reported so the cleanup commit deletes it.
        String planted = "docs/architecture/index.adoc|R999999";

        assertThat(TransientCitationCheck.staleBaselineEntries(repoRoot(), Set.of(planted)))
            .containsExactly(planted);
    }

    @Test
    void aBaselineEntryStillCited_isNotStale() throws IOException {
        // The other direction: the real baseline is exactly the live population, so nothing in it
        // is stale today. This is what makes the burn-down assertion a live check rather than a
        // list that happens to pass.
        assertThat(TransientCitationCheck.staleBaselineEntries(
            repoRoot(), TransientCitationCheck.KNOWN_CITATIONS)).isEmpty();
    }

    @Test
    void aRootWithoutTheWorkflowAnchor_isNotJudgedAgainstTheBaseline(@TempDir Path dir) throws IOException {
        // The baseline names paths in this repository. Against any other root every entry would
        // read as stale, so the anchor decides whether the burn-down invariant even applies.
        writeBothDocs(dir, "# Clean\n\nNo citations.\n", "# Web env\n\nClean.\n");

        assertThat(TransientCitationCheck.isBaselineRoot(dir)).isFalse();
        assertThat(TransientCitationCheck.isBaselineRoot(repoRoot())).isTrue();
        assertThat(TransientCitationCheck.run(List.of(dir.toString()))).isZero();
    }

    @Test
    void bothPublishedDocTreesAreScanned() {
        // The scope is a decision, not an accident of where the survey happened to look. Both
        // trees render to the same public site, where the roadmap directory is not the reader's
        // to search, so an id is exactly as unresolvable on an author-facing page as on a
        // contributor-facing one. Narrowing this list back needs a reason stated here.
        assertThat(TransientCitationCheck.SCANNED_TREES)
            .containsExactlyInAnyOrder("docs/architecture", "docs/manual");
    }

    @Test
    void everyDeclaredTreeResolvesInThisRepository() throws IOException {
        // The anti-vacuous floor, checked against the real tree rather than a fixture: a declared
        // tree that has been renamed would pass every fixture case above and scan nothing here.
        for (String tree : TransientCitationCheck.SCANNED_TREES) {
            assertThat(TransientCitationCheck.pagesUnder(repoRoot().resolve(tree)))
                .as("declared tree %s must hold pages in this repository", tree)
                .isNotEmpty();
        }
    }

    @Test
    void everyBaselineEntryNamesAPageAndACitation() {
        assertThat(TransientCitationCheck.KNOWN_CITATIONS)
            .as("the baseline is keyed by page and citation so one page's cleanup cannot excuse "
                + "another's; a malformed entry would match nothing and never go stale")
            .allSatisfy(entry -> assertThat(entry).matches("^docs/architecture/[\\w./-]+\\.adoc\\|R\\d+$"));
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
