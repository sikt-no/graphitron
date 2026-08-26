package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments.Document;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the outcome block's contract: what it renders, what it refuses to render, and what it says
 * about a fixture that classifies without generating.
 *
 * <p>Renders are expensive here (each one captures a fixture into a store and runs the generator),
 * so these cases use one document of each kind rather than sweeping the corpus.
 * {@link CorpusFragmentTest} is the sweep.
 */
@PipelineTier
class OutcomeBlockRendererTest {

    /** A fixture the generator accepts, so the block carries emitted names. */
    private static final String GENERATES = "catalog";

    /** A fixture that pins a verdict on a pattern the build rejects, so it emits nothing. */
    private static final String CLASSIFIES_ONLY = "mapping";

    private static Document document(String id) {
        return CorpusDocuments.withProjection().stream()
            .filter(e -> e.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no document with a projection named '" + id + "' in the corpus"));
    }

    @Test
    void aGeneratingExampleRendersVerdictsAndEmittedNames(@TempDir Path dir) throws IOException {
        String block = OutcomeBlockRenderer.render(document(GENERATES), dir);

        assertThat(block)
            .as("the block is a three-column AsciiDoc table: what the coordinate is, what the "
                + "store makes of it, and what the generator emitted for it")
            .contains("[cols=\"1,1,2\"]")
            .contains("| Coordinate | Verdict | Emitted")
            .contains("|===");
        assertThat(block)
            .as("the verdict is spelled in the intent view's own closed vocabulary, with its tier")
            .contains("`TABLE_COLUMN`, inferred");
        assertThat(block)
            .as("a coordinate's emitted entry point is the field's own name on its parent's "
                + "fetchers class, and the block shows a signature")
            .contains("`fetchers.QueryFetchers#film(DataFetchingEnvironment)`");
    }

    @Test
    void aCoordinateWithNoResolvedClaimSaysSo(@TempDir Path dir) throws IOException {
        // The lean form's honest answer: this coordinate carries no claiming directive and matches
        // no column, so the verdict layer has nothing to say about it. Spelling that out beats an
        // empty cell, which reads as an omission rather than a fact.
        assertThat(OutcomeBlockRenderer.render(document(GENERATES), dir))
            .contains("no claiming directive");
    }

    @Test
    void aFixtureThatDoesNotGenerateRendersTheVerdictHalfAndSaysWhy(@TempDir Path dir) throws IOException {
        String block = OutcomeBlockRenderer.render(document(CLASSIFIES_ONLY), dir);

        assertThat(block)
            .as("the corpus is a classification corpus: a fixture earns its place by pinning a "
                + "verdict, not by producing output, so the emitted column is dropped rather than "
                + "filled with blanks")
            .contains("[cols=\"1,1\"]")
            .contains("| Coordinate | Verdict")
            .doesNotContain("| Emitted");
        assertThat(block)
            .as("and 'no emitted names' is stated, so a reader sees a fact about the document "
                + "rather than a hole in the table")
            .contains("classifies but does not generate")
            .contains("xref:../explanation/typed-rejection.adoc[Typed rejection]");
        assertThat(OutcomeBlockRenderer.run(document(CLASSIFIES_ONLY), dir).generated()).isFalse();
    }

    @Test
    void theBlockRendersNoCommandRowsAndNoBodies(@TempDir Path dir) throws IOException {
        // The design's two refusals. Row identity is not a shipped obligation, so a doc-guarded
        // verbatim command-row block would reinstate it; and code-string assertions on generated
        // bodies are banned across every tier, so a name and a parameter list is the ceiling.
        String block = OutcomeBlockRenderer.render(document(GENERATES), dir);

        assertThat(block)
            .as("no command relation, command row, or plan vocabulary reaches the page here")
            .doesNotContain("Command")
            .doesNotContain("LauncherRelation")
            .doesNotContain("EmitPlan");
        assertThat(block)
            .as("a rendered method is a signature; a body would bring braces and statements")
            .doesNotContain("return ")
            .doesNotContain("{");
    }

    @Test
    void theRenderIsStableAcrossRuns(@TempDir Path first, @TempDir Path second) throws IOException {
        // The guard asserts the block verbatim, so an unstable order or a leaked temp path would
        // fail the build on every run for no reason.
        assertThat(OutcomeBlockRenderer.render(document(GENERATES), first))
            .isEqualTo(OutcomeBlockRenderer.render(document(GENERATES), second));
    }

    @Test
    void theBlockNamesExactlyTheCoordinatesTheExampleShows(@TempDir Path dir) throws IOException {
        // The two halves of one document must agree about what the document is. The coordinates come
        // from the same projection walk that renders the SDL, never from a second derivation.
        Document document = document(GENERATES);
        var touched = QueryViewRenderer.touchedCoordinates(document.sdl(), document.projection());
        var expected = touched.entrySet().stream()
            .flatMap(e -> e.getValue().stream().map(field -> e.getKey() + "." + field))
            .sorted()
            .toList();

        assertThat(OutcomeBlockRenderer.run(document, dir).outcomes())
            .extracting(OutcomeBlockRenderer.Outcome::coordinate)
            .containsExactlyElementsOf(expected);
    }

    @Test
    void anEditedBlockIsNotInTheApprovedFragment(@TempDir Path dir) throws IOException {
        // The planted regression, in the direction the approval exists for: the real block is what
        // the committed fragment carries (CorpusFragmentTest asserts that for every document), and a
        // block with one verdict changed is not, so an edit to either side fails the build instead
        // of passing quietly.
        String real = OutcomeBlockRenderer.render(document(GENERATES), dir);
        String tampered = real.replace("`TABLE_COLUMN`, inferred", "`TABLE_COLUMN`, authored");
        assertThat(tampered).isNotEqualTo(real);

        String fragment = Files.readString(CorpusFragmentRenderer.fragmentFile(GENERATES));
        assertThat(fragment).contains(real);
        assertThat(fragment)
            .as("a tampered verdict must not be found in the approved fragment, or the approval "
                + "would be holding content the pipeline never produced")
            .doesNotContain(tampered);
    }
}
