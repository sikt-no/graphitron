package no.sikt.graphitron.mcp.rag.docs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier (R385): {@link AdocChunker} produces heading-aware chunks from raw {@code .adoc} syntax.
 * Pins the section-boundary breadcrumbs, the {@code // rag:split} override, that a near-miss comment
 * does not masquerade as a split, and that {@code ==}-prefixed lines inside a fenced block are body,
 * not headings.
 */
class AdocChunkerTest {

    @Test
    void nestedSectionsProduceTheExpectedHeadingPaths() {
        String adoc = """
            = Pagination
            Intro prose under the title.

            == Keyset seek
            Keyset body.

            === Stable cursors
            Cursor body.

            == Offset
            Offset body.
            """;
        List<DocChunk> chunks = AdocChunker.chunk(adoc, "docs/manual/x.adoc");

        assertThat(chunks).extracting(DocChunk::headingPath).containsExactly(
            List.of("Pagination"),
            List.of("Pagination", "Keyset seek"),
            List.of("Pagination", "Keyset seek", "Stable cursors"),
            List.of("Pagination", "Offset"));
        // The title chunk carries the preamble as its body; the heading line itself is not in the text.
        assertThat(chunks.getFirst().text()).isEqualTo("Intro prose under the title.");
        // The anchor is the slug of the heading (idprefix empty, idseparator '-').
        assertThat(chunks.get(1).anchor()).isEqualTo("keyset-seek");
        // embedText prepends the breadcrumb so the chunk keeps its context.
        assertThat(chunks.get(2).embedText())
            .isEqualTo("Pagination > Keyset seek > Stable cursors\nCursor body.");
    }

    @Test
    void ragSplitForcesAnExtraBoundaryWithinASectionInheritingTheHeadingPath() {
        String adoc = """
            = Doc
            == Section
            First half.

            // rag:split
            Second half.
            """;
        List<DocChunk> chunks = AdocChunker.chunk(adoc, "docs/manual/x.adoc");

        // The title (empty body) plus the two halves of the one section.
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(1).headingPath()).containsExactly("Doc", "Section");
        assertThat(chunks.get(1).text()).isEqualTo("First half.");
        // The second half inherits the same breadcrumb and anchor, no new heading.
        assertThat(chunks.get(2).headingPath()).containsExactly("Doc", "Section");
        assertThat(chunks.get(2).text()).isEqualTo("Second half.");
        assertThat(chunks.get(2).anchor()).isEqualTo("section");
    }

    @Test
    void aMalformedRagPrefixedCommentDoesNotMasqueradeAsASplit() {
        String adoc = """
            = Doc
            == Section
            Body before.
            // rag:splitt
            // rag:
            //rag:split
            Body after.
            """;
        List<DocChunk> chunks = AdocChunker.chunk(adoc, "docs/manual/x.adoc");

        // Only the title + the one section: no near-miss comment created a boundary, and the comment
        // lines themselves are dropped from the body.
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).text()).isEqualTo("Body before.\nBody after.");
    }

    @Test
    void headingSyntaxInsideAFencedBlockIsBodyNotASection() {
        String adoc = """
            = Doc
            == Section
            Here is a shell sample:

            ----
            == not a heading
            === also not a heading
            ----

            Trailing prose.
            """;
        List<DocChunk> chunks = AdocChunker.chunk(adoc, "docs/manual/x.adoc");

        // The two `==` lines live inside the listing block, so only the title + the one section emit.
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).headingPath()).containsExactly("Doc", "Section");
        assertThat(chunks.get(1).text()).contains("== not a heading").contains("Trailing prose.");
    }

    @Test
    void anExplicitBlockAnchorOverridesTheSlug() {
        String adoc = """
            = Doc
            [[custom-id]]
            == Section Title
            Body.
            """;
        List<DocChunk> chunks = AdocChunker.chunk(adoc, "docs/manual/x.adoc");
        assertThat(chunks.get(1).anchor()).isEqualTo("custom-id");
    }
}
