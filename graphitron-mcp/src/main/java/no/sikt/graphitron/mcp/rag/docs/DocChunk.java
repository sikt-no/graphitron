package no.sikt.graphitron.mcp.rag.docs;

import java.util.List;

/**
 * One heading-aware chunk of a documentation {@code .adoc} file: the unit the docs RAG index
 * embeds and the unit {@code docs.search} returns. Produced by {@link AdocChunker}, embedded by
 * {@link DocsIndexBuilder} at build time, and reconstructed from the bundle by {@link DocsBundle} at
 * warm time.
 *
 * <p><strong>One string feeds both the lexical and the semantic half.</strong> {@link #embedText()}
 * prepends the heading path before the body so a chunk keeps its context ("Pagination &gt; Keyset
 * seek &gt; ..." rather than an orphaned paragraph); the same string is the BM25 text and the text
 * embedded into the KNN vector. The {@code Embedder.Embedding} record bundles that text with its
 * vector, so the lexical and semantic views cannot drift.
 *
 * @param headingPath the ordered ancestor headings down to this chunk's own heading; the display
 *                    breadcrumb and the embed-context prefix
 * @param sourcePath  the repo-relative {@code .adoc} path (e.g. {@code docs/manual/.../table.adoc}),
 *                    the stable half of the chunk {@link #id()} and the basis of the rendered-site
 *                    deep link
 * @param anchor      the section's id/slug for deep-linking into the rendered page; empty for a
 *                    page-title chunk that carries no section anchor
 * @param text        the chunk body (the section prose, with the heading line and AsciiDoc line
 *                    comments stripped); the displayed passage
 */
public record DocChunk(List<String> headingPath, String sourcePath, String anchor, String text) {

    public DocChunk {
        headingPath = List.copyOf(headingPath);
    }

    /** The separator joining the heading breadcrumb, both for embedding context and display. */
    public static final String HEADING_SEPARATOR = " > ";

    /**
     * The stable, deep-linkable chunk ID: {@code sourcePath#anchor}. The same shape a result returns,
     * and the {@code id} the bundle and store round-trip. {@code sourcePath} carries no {@code #}
     * (it is a file path), so the form splits unambiguously on the last {@code #}.
     */
    public String id() {
        return sourcePath + "#" + anchor;
    }

    /**
     * The text actually embedded and lexically indexed: the heading breadcrumb prepended to the body,
     * so the vector and the BM25 tokens both see the chunk's context, not just an orphaned paragraph.
     */
    public String embedText() {
        String prefix = String.join(HEADING_SEPARATOR, headingPath);
        return prefix.isEmpty() ? text : prefix + "\n" + text;
    }
}
