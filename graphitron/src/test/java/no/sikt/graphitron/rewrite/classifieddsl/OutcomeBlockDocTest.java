package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments.Document;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other half of the doc-bridge guard. {@link ClassifiedDocTest} holds each worked example's
 * SDL against the corpus, so the schema pattern on the page cannot drift; this holds what the page
 * says graphitron <em>does</em> with that pattern, so the answer beside it cannot drift either.
 *
 * <p>For each document carrying a projection this re-runs {@link OutcomeBlockRenderer} over the
 * fixture, capturing its facts and generating from it, and asserts the page still contains exactly
 * the rendered block. To update one, run this test and paste the block from the failure message.
 *
 * <p>Before this existed, the "what gets generated" half of every worked example was ungated prose: a
 * verdict restated by hand beside a machine-checked schema. That asymmetry is what let the page
 * accumulate leaf names for classifications the walk had stopped producing.
 */
@PipelineTier
class OutcomeBlockDocTest {

    /** The page carrying the worked examples, resolved the way {@link ClassifiedDocTest} does. */
    private static final List<Path> PAGE_CANDIDATES = List.of(
        Path.of("..", "docs", "architecture", "reference", "code-generation-triggers.adoc"),
        Path.of("docs", "architecture", "reference", "code-generation-triggers.adoc"));

    static Stream<Document> documentsWithProjection() {
        return CorpusDocuments.withProjection().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentsWithProjection")
    void pageStatesTheOutcomeEveryProjectionActuallyProduces(Document document, @TempDir Path workDir)
            throws IOException {
        String page = Files.readString(page());
        String rendered = OutcomeBlockRenderer.render(document, workDir);

        assertThat(page)
            .as("code-generation-triggers.adoc must contain the outcome block rendered for doc "
                + "document '%s'. The block states each coordinate's verdict from the store and the "
                + "methods the generator emitted for it, so a change to either shows up here "
                + "rather than leaving the page quietly wrong. Paste this under the document's SDL "
                + "block:%n%n%s%n", document.id(), rendered)
            .contains(rendered);
    }

    private static Path page() {
        return PAGE_CANDIDATES.stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "could not locate code-generation-triggers.adoc from working dir "
                + Path.of("").toAbsolutePath()));
    }
}
