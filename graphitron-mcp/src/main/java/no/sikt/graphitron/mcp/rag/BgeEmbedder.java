package no.sikt.graphitron.mcp.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;

import java.util.ArrayList;
import java.util.List;

/**
 * The bge-backed {@link Embedder} (R372 D1): English-only {@code bge-small-en-v1.5-q} (384-dim,
 * MIT), wrapping langchain4j's in-process ONNX embedding model. The ONNX model is bundled in the
 * langchain4j jar, so construction loads it from the classpath with no network fetch; that load is
 * heavy, so the async-warm loader instantiates this off the dev thread (R372 D3).
 *
 * <p>This adapter owns the one piece of bge-specific business logic: the query-instruction prefix,
 * applied in {@link #embedQuery} and nowhere else. Keeping it here rather than at every call site
 * is the same separation-of-business-logic-from-API axis the {@code graphitron-lsp} /
 * {@code graphitron-mcp} split already serves; langchain4j's {@code EmbeddingModel} is an
 * implementation detail of this class, never the published seam.
 */
public final class BgeEmbedder implements Embedder {

    /**
     * bge-small-en-v1.5's query-side instruction. bge is asymmetric: this prefix is prepended to a
     * query before embedding so its vector lands near the passages that answer it, and is never
     * applied to documents. It conditions the vector only; the {@link Query} record still carries
     * the caller's original text for BM25.
     */
    private static final String QUERY_INSTRUCTION =
        "Represent this sentence for searching relevant passages: ";

    private final EmbeddingModel model;

    /** Production constructor: loads the bundled bge ONNX model (the heavy, warm-time work). */
    public BgeEmbedder() {
        this(new BgeSmallEnV15QuantizedEmbeddingModel());
    }

    /** Seam for tests to inject a recording / stub model without loading ONNX. */
    BgeEmbedder(EmbeddingModel model) {
        this.model = model;
    }

    @Override
    public Query embedQuery(String text) {
        float[] vector = model.embed(QUERY_INSTRUCTION + text).content().vector();
        return new Query(text, vector);
    }

    @Override
    public List<Embedding> embedDocuments(List<String> texts) {
        var segments = texts.stream().map(TextSegment::from).toList();
        var vectors = model.embedAll(segments).content();
        var out = new ArrayList<Embedding>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            out.add(new Embedding(texts.get(i), vectors.get(i).vector()));
        }
        return out;
    }

    @Override
    public int dimension() {
        return model.dimension();
    }
}
