package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus.Example;
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
 * R281 slice 2 doc-bridge guard: the {@code code-generation-triggers} page renders its taxonomy from
 * the corpus, so every documentation example's rendered SDL must appear verbatim on the page. For each
 * {@link ClassifiedCorpus#docExamples() doc example} this re-runs {@link QueryViewRenderer} over the
 * fixture and projection query and asserts the page still contains exactly that block.
 *
 * <p>This is the anti-drift mechanism behind the "doc as a map into the tests" form (Spec
 * §"Rendering: queries as views over the corpus"): the page holds the SDL inline so a contributor
 * reads it without indirection, and this test fails the build if the page ever diverges from what the
 * live corpus renders. To add or update an example, run this test, copy the rendered block from the
 * failure message into the page under its prose, and commit.
 */
@PipelineTier
class ClassifiedDocTest {

    private static final List<Path> PAGE_CANDIDATES = List.of(
        Path.of("..", "docs", "architecture", "reference", "code-generation-triggers.adoc"),
        Path.of("docs", "architecture", "reference", "code-generation-triggers.adoc"));

    static Stream<Example> docExamples() {
        return ClassifiedCorpus.docExamples().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("docExamples")
    void pageRendersEveryDocExampleFromTheCorpus(Example example) throws IOException {
        String page = Files.readString(page());
        String rendered = QueryViewRenderer.render(example.sdl(), example.query());

        assertThat(page)
            .as("code-generation-triggers.adoc must contain the SDL rendered for doc example '%s' "
                + "(query %s). Paste this block into the page under its prose:%n%n%s%n",
                example.id(), example.query(), rendered)
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
