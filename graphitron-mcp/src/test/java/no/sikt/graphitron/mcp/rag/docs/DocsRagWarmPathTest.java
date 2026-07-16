package no.sikt.graphitron.mcp.rag.docs;

import no.sikt.graphitron.mcp.rag.Embedder;
import no.sikt.graphitron.mcp.rag.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier: the <em>production</em> warm path end to end. A bundle of pre-embedded tuples
 * is rebuilt into an in-memory store through the real {@link DocsRag#loadDocsIndex} loader (the same
 * code the server warm runs), and a query lands the expected passage. No ONNX: the vectors are planted,
 * so this exercises the reconstruction loader without the build-time embed cost (that load is covered
 * by the {@code @Tag("slow")} embedder test).
 */
class DocsRagWarmPathTest {

    @Test
    void bundleRebuildsThroughTheProductionLoaderAndSearchReturnsAKnownPassage() {
        var keyset = new DocChunk(List.of("Batching", "Keyset seek"),
            "docs/manual/explanation/batching-model.adoc", "keyset-seek",
            "Use keyset pagination for stable cursors.");
        var loader = new DocChunk(List.of("Batching", "Data loader"),
            "docs/manual/explanation/batching-model.adoc", "data-loader",
            "The data loader batches sibling fetches.");

        var buf = new ByteArrayOutputStream();
        DocsBundle.write(buf, 3, List.of(
            entry(keyset, new float[] {1, 0, 0}),
            entry(loader, new float[] {0, 1, 0})));

        try (DocsIndex index = DocsRag.loadDocsIndex(new ByteArrayInputStream(buf.toByteArray()))) {
            assertThat(index.dimension()).isEqualTo(3);

            // A query vector nearest the keyset passage; KNN puts it first, and the payload round-trips.
            List<EmbeddingStore.Hit> hits =
                index.store().search(new Embedder.Query("stable cursors", new float[] {1, 0, 0}), 2);

            assertThat(hits).isNotEmpty();
            assertThat(hits.getFirst().id()).isEqualTo(keyset.id());
            DocChunk top = DocsBundle.decodePayload(hits.getFirst().payload());
            assertThat(top.headingPath()).containsExactly("Batching", "Keyset seek");
            assertThat(top.text()).isEqualTo("Use keyset pagination for stable cursors.");
        }
    }

    private static DocsBundle.Entry entry(DocChunk chunk, float[] vector) {
        return new DocsBundle.Entry(chunk.id(), chunk.embedText(), DocsBundle.encodePayload(chunk), vector);
    }
}
