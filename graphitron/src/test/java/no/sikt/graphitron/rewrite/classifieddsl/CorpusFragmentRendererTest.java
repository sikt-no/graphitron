package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.ArchitectureDocSymbolScanner;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planted regressions under the placement floors, and the naming contract the page depends on.
 *
 * <p>{@link CorpusFragmentTest} sweeps the real tree, where a green run says only that no fragment
 * is currently misplaced. These cases hand each floor the defect it exists to catch, so a floor that
 * stops firing fails here rather than passing quietly over a drained corpus.
 *
 * <p>Unit tier: a placement question is a comparison of three sets of names, and answering it needs
 * neither a store nor a render.
 */
@UnitTier
class CorpusFragmentRendererTest {

    @Test
    void aFragmentWhoseDocumentIsGoneIsAnOrphan() {
        assertThat(CorpusFragmentRenderer.orphanFragments(List.of("catalog", "retired"),
            Set.of("catalog")))
            .as("a document deleted or stripped of its projection leaves a fragment that renders "
                + "from nothing, and the page would go on publishing it")
            .containsExactly("retired");
        assertThat(CorpusFragmentRenderer.orphanFragments(List.of("catalog"), Set.of("catalog")))
            .isEmpty();
    }

    @Test
    void aDocumentWithAProjectionAndNoFragmentIsMissingOne() {
        assertThat(CorpusFragmentRenderer.missingFragments(List.of("catalog"),
            Set.of("catalog", "brand-new")))
            .as("a new worked example is a document plus a fragment plus an include; without the "
                + "fragment the example exists in the corpus and nowhere a reader looks")
            .containsExactly("brand-new");
        assertThat(CorpusFragmentRenderer.missingFragments(List.of("catalog", "brand-new"),
            Set.of("catalog", "brand-new")))
            .isEmpty();
    }

    @Test
    void aFragmentNoPageIncludesIsUnplaced() {
        String page = "prose\n" + CorpusFragmentRenderer.includeLine("catalog") + "\nmore prose\n";

        assertThat(CorpusFragmentRenderer.notIncluded(List.of("catalog", "unplaced"), page))
            .containsExactly("unplaced");
        assertThat(CorpusFragmentRenderer.notIncluded(List.of("catalog"), page)).isEmpty();
    }

    @Test
    void namingAFragmentInProseDoesNotCountAsIncludingIt() {
        assertThat(CorpusFragmentRenderer.notIncluded(List.of("catalog"),
            "see `" + CorpusFragmentRenderer.fileName("catalog") + "` for the rendered example\n"))
            .as("the floor is about what a reader sees, so it matches the include line and not the "
                + "filename: prose mentioning the file renders none of it")
            .containsExactly("catalog");
    }

    @Test
    void theFragmentOpensWithTheGeneratedRegionMarker() {
        // The marker does two jobs, and both need it on the first line: it tells a human not to edit
        // the file, and it opens the region the architecture symbol scan skips. A rendered
        // coordinate names GraphQL types, so scanning the fragment would report Film.title as a
        // dangling Java type.
        assertThat(CorpusFragmentRenderer.HEADER)
            .startsWith(ArchitectureDocSymbolScanner.GENERATED_BLOCK_MARKER)
            .contains("Do not edit");
    }

    @Test
    void theFragmentIsAnUnderscoreFileSoAsciidoctorTreatsItAsAnInclude() {
        // A staged .adoc without the prefix is rendered as a standalone page, which would publish
        // 32 title-less fragments beside the page that includes them.
        assertThat(CorpusFragmentRenderer.fileName("catalog"))
            .isEqualTo("_example-catalog.adoc")
            .startsWith("_");
        assertThat(CorpusFragmentRenderer.includeLine("catalog"))
            .isEqualTo("include::_example-catalog.adoc[]");
    }
}
