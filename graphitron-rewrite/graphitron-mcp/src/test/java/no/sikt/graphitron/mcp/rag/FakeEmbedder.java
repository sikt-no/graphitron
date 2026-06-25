package no.sikt.graphitron.mcp.rag;

import java.util.List;

/**
 * A trivial {@link Embedder} for warm-lifecycle tests that only need a non-null handle of the right
 * type, not real vectors. Returns zero-filled vectors of the configured width.
 */
final class FakeEmbedder implements Embedder {

    private final int dimension;

    FakeEmbedder(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public Query embedQuery(String text) {
        return new Query(text, new float[dimension]);
    }

    @Override
    public List<Embedding> embedDocuments(List<String> texts) {
        return texts.stream().map(t -> new Embedding(t, new float[dimension])).toList();
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
