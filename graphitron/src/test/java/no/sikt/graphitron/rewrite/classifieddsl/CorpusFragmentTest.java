package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments.Document;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval over every worked example's fragment, and the placement floors that keep the page and
 * the corpus talking about the same set of examples.
 *
 * <p><b>What replaced what.</b> Two guards used to hold the documentation page: one asserting that
 * the page contained each document's rendered SDL, one asserting that it contained each document's
 * outcome table. Both worked by containment against a hand-pasted page, so updating an example meant
 * running a test and pasting a block out of its failure message. Here the rendered pair is a file of
 * its own, the page includes it, and the comparison is file against file. The paste loop becomes a
 * regeneration: run this test, read the render it leaves under {@code target/corpus-fragments}, and
 * run the {@code cp} its message gives you if the new output is right. That is the approval idiom
 * this tree already uses for query responses, and the reason the render lands under {@code target}
 * rather than beside the approved file is that the approved files live in the published documentation
 * tree, where a stray {@code .adoc} would be staged and scanned as an untracked page.
 *
 * <p><b>Why the fragment is committed.</b> {@link CorpusFragmentRenderer} carries the reasoning: the
 * emitted names in the outcome table are pinned nowhere else, so the fragment is an oracle and has
 * to be checked in to be one. Committing it also means the page's {@code include::} resolves in every
 * build, including the ones that skip tests and therefore could never have rendered it.
 *
 * <p><b>Three floors under the approval.</b> An approval sweep says only that nothing currently
 * disagrees; it cannot notice an example that left the sweep. So: every document carrying a
 * projection has a fragment, every fragment has a document, and every fragment is included by an
 * authored page. Each failure names the file and the direction, because the fix differs: a missing
 * fragment is a regeneration, an orphan fragment is a deletion, and a missing include is an edit to
 * the page's teaching order, which is a human's call and never this test's to make.
 */
@PipelineTier
class CorpusFragmentTest {

    static Stream<Document> documentsWithProjection() {
        return CorpusDocuments.withProjection().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentsWithProjection")
    void theFragmentIsWhatTheCorpusRenders(Document document, @TempDir Path workDir) throws IOException {
        Path approved = CorpusFragmentRenderer.fragmentFile(document.id());
        String rendered = CorpusFragmentRenderer.render(document, workDir);
        String committed = Files.isRegularFile(approved) ? Files.readString(approved) : null;

        if (rendered.equals(committed)) {
            Files.deleteIfExists(renderFile(document));
            return;
        }
        Path left = renderFile(document);
        Files.writeString(left, rendered);

        assertThat(committed)
            .as("the committed fragment for '%s' must be exactly what the corpus renders: the SDL "
                + "its projection touches, and the verdicts and emitted names a real run produces "
                + "for those coordinates. This run's render is in %s; diff it against the approved "
                + "file, and if the new output is right, copy it over:%n%n  cp %s %s%n",
                document.id(), left, left, approved)
            .isEqualTo(rendered);
    }

    @Test
    void everyCommittedFragmentHasADocumentThatRendersIt() throws IOException {
        assertThat(CorpusFragmentRenderer.orphanFragments(
                CorpusFragmentRenderer.committedIds(), documentIds()))
            .as("a fragment whose document was deleted or lost its projection renders from nothing "
                + "and would go on being published. Delete the file.")
            .isEmpty();
    }

    @Test
    void everyDocumentWithAProjectionHasAFragment() throws IOException {
        assertThat(CorpusFragmentRenderer.missingFragments(
                CorpusFragmentRenderer.committedIds(), documentIds()))
            .as("a document carrying a projection is a worked example, and a worked example with no "
                + "fragment is one the documentation cannot show. Run this test to render it.")
            .isEmpty();
    }

    @Test
    void everyFragmentIsIncludedByAnAuthoredPage() throws IOException {
        assertThat(CorpusFragmentRenderer.notIncluded(
                CorpusFragmentRenderer.committedIds(), authoredPages()))
            .as("a fragment no page includes is a rendered example nobody reads: the render cost is "
                + "paid and the reader is not served. Add '%s' where the example belongs in the "
                + "page's teaching order.",
                CorpusFragmentRenderer.includeLine("<id>"))
            .isEmpty();
    }

    /** The ids of every document carrying a projection, which is the set a fragment answers to. */
    private static Set<String> documentIds() {
        return CorpusDocuments.withProjection().stream()
            .map(Document::id)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Every authored page in the fragments' own directory, concatenated, for the include scan.
     *
     * <p>Scoped to that one directory rather than the whole docs tree, and not as a shortcut: an
     * {@code include::} resolves relative to the including page, so a fragment sitting here can only
     * be included from here without a path. A page elsewhere that wanted one would be writing a
     * different include line than the floor looks for, which is a change to where the fragments live
     * and should fail this scan rather than pass it quietly.
     */
    private static String authoredPages() throws IOException {
        StringBuilder all = new StringBuilder();
        try (var files = Files.list(CorpusFragmentRenderer.directory())) {
            for (Path page : files.filter(p -> p.getFileName().toString().endsWith(".adoc"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted()
                    .toList()) {
                all.append(Files.readString(page)).append('\n');
            }
        }
        return all.toString();
    }

    /** Where this run's render for {@code document} is left when it disagrees with the approval. */
    private static Path renderFile(Document document) throws IOException {
        return CorpusFragmentRenderer.renderDirectory()
            .resolve(CorpusFragmentRenderer.fileName(document.id()));
    }
}
