package no.sikt.graphitron.mcp.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seam-tier (R372): the bge query/document asymmetry is applied inside {@link BgeEmbedder} on the
 * {@code embedQuery} path only, and the {@link Embedder.Query} / {@link Embedder.Embedding} records
 * carry the source text alongside the vector. A recording {@link EmbeddingModel} captures the exact
 * strings handed to the model, so the assertion is on the instruction-prefix routing, not on ONNX.
 */
class EmbedderAsymmetryTest {

    /** Records the text it is asked to embed and returns a fixed-width dummy vector. */
    private static final class RecordingModel implements EmbeddingModel {
        final List<String> embedded = new ArrayList<>();

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            var out = new ArrayList<Embedding>(segments.size());
            for (var segment : segments) {
                embedded.add(segment.text());
                out.add(Embedding.from(new float[] {1.0f, 0.0f, 0.0f}));
            }
            return Response.from(out);
        }

        @Override
        public int dimension() {
            return 3;
        }
    }

    @Test
    void embedQueryAppliesThePrefixToTheVectorOnlyAndKeepsTheOriginalText() {
        var model = new RecordingModel();
        var embedder = new BgeEmbedder(model);

        var query = embedder.embedQuery("how do connections page");

        // The vector was embedded from the instruction-prefixed form...
        assertThat(model.embedded).hasSize(1);
        assertThat(model.embedded.get(0))
            .startsWith("Represent this sentence for searching relevant passages: ")
            .endsWith("how do connections page");
        // ...while the record carries the caller's original, unprefixed text for BM25.
        assertThat(query.text()).isEqualTo("how do connections page");
        assertThat(query.vector()).hasSize(3);
    }

    @Test
    void embedDocumentsAppliesNoPrefixAndPairsEachVectorWithItsSourceText() {
        var model = new RecordingModel();
        var embedder = new BgeEmbedder(model);

        var docs = embedder.embedDocuments(List.of("first passage", "second passage"));

        // No instruction prefix on the document path.
        assertThat(model.embedded).containsExactly("first passage", "second passage");
        // Each Embedding carries its own source text alongside its vector, positionally aligned.
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).text()).isEqualTo("first passage");
        assertThat(docs.get(1).text()).isEqualTo("second passage");
        assertThat(docs.get(0).vector()).hasSize(3);
    }

    @Test
    void dimensionDelegatesToTheBackingModel() {
        assertThat(new BgeEmbedder(new RecordingModel()).dimension()).isEqualTo(3);
    }
}
