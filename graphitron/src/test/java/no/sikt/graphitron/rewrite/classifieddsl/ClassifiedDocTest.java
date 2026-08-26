package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments.Document;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Doc-bridge guard: the {@code code-generation-triggers} page renders its taxonomy from
 * the corpus, so every worked example's rendered SDL must appear verbatim on the page. For each
 * {@link CorpusDocuments#withProjection() document carrying a projection} this re-runs
 * {@link QueryViewRenderer} over the fixture and the projection and asserts the page still contains
 * exactly that block.
 *
 * <p>This is the anti-drift mechanism behind the "doc as a map into the tests" form (Spec
 * §"Rendering: queries as views over the corpus"): the page holds the SDL inline so a contributor
 * reads it without indirection, and this test fails the build if the page ever diverges from what the
 * live corpus renders. To add or update a worked example, run this test, copy the rendered block from
 * the failure message into the page under its prose, and commit.
 */
@PipelineTier
class ClassifiedDocTest {

    private static final List<Path> PAGE_CANDIDATES = List.of(
        Path.of("..", "docs", "architecture", "reference", "code-generation-triggers.adoc"),
        Path.of("docs", "architecture", "reference", "code-generation-triggers.adoc"));

    static Stream<Document> documentsWithProjection() {
        return CorpusDocuments.withProjection().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentsWithProjection")
    void pageRendersEveryProjectionFromTheCorpus(Document document) throws IOException {
        String page = Files.readString(page());
        String rendered = QueryViewRenderer.render(document.sdl(), document.projection());

        assertThat(page)
            .as("code-generation-triggers.adoc must contain the SDL rendered for document '%s' "
                + "(query %s). Paste this block into the page under its prose:%n%n%s%n",
                document.id(), document.projection(), rendered)
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
