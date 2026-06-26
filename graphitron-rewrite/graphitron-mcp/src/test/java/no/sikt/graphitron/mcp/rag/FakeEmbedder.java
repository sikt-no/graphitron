package no.sikt.graphitron.mcp.rag;

import java.util.List;

/**
 * A trivial {@link Embedder} for tests that need a real-typed handle without loading ONNX. Produces
 * a deterministic, <em>non-zero</em> one-hot vector keyed off the text hash: non-zero so a Lucene
 * COSINE index accepts and searches it (a zero vector has no direction under cosine), deterministic
 * so a persisted index round-trips, and text-keyed so distinct documents do not all collapse onto
 * one vector. Retrieval quality is the ONNX test's job; here BM25 over the descriptor text carries
 * the ranking and these vectors only have to be well-formed.
 */
public final class FakeEmbedder implements Embedder {

    private final int dimension;

    public FakeEmbedder(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public Query embedQuery(String text) {
        return new Query(text, oneHot(text, dimension));
    }

    @Override
    public List<Embedding> embedDocuments(List<String> texts) {
        return texts.stream().map(t -> new Embedding(t, oneHot(t, dimension))).toList();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    /** A unit vector with a single 1.0 component chosen by the text hash: always non-zero. */
    public static float[] oneHot(String text, int dimension) {
        var vector = new float[dimension];
        vector[Math.floorMod(text.hashCode(), dimension)] = 1.0f;
        return vector;
    }
}
