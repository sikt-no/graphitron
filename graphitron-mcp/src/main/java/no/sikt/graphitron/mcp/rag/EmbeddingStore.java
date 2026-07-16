package no.sikt.graphitron.mcp.rag;

import java.util.List;

/**
 * The vector-store seam: write a document, hybrid-search by query, and round-trip an
 * opaque payload + stable ID per hit. Backend choice never reaches a consumer; nothing in this
 * signature names Lucene segments, BM25 tuning, or HNSW parameters. The sole shipping backend is
 * {@link LuceneEmbeddingStore} (hybrid BM25 + KNN in one index); a RAM-directory instance of that
 * same class is the seam's fake for fast unit tests, so the seam is exercised without a second
 * in-memory implementation drifting from it.
 *
 * <p>The seam consumes the D1 {@link Embedder.Embedding} / {@link Embedder.Query} records rather
 * than bare {@code float[]} + text pairs, so it reads the KNN vector and the BM25 text off one
 * object instead of trusting two positional arguments to correspond.
 *
 * <p><strong>Construction (build vs load) is backend-specific by design.</strong> The seam covers
 * the operations a consumer performs; building a fresh index by embedding documents and loading a
 * prebuilt index off a path are construction concerns that pick a concrete backend, so they live as
 * static factories on the implementation ({@link LuceneEmbeddingStore#inMemory},
 * {@link LuceneEmbeddingStore#building}, {@link LuceneEmbeddingStore#load}). A consumer's async-warm
 * loader callable picks one; the tool code that then queries sees only this seam.
 */
public interface EmbeddingStore extends AutoCloseable {

    /**
     * Add one document under a stable {@code id}, with an opaque {@code payload} the store
     * round-trips verbatim on a hit. The embedding's vector width is validated against the store's
     * configured dimension and fails loudly here on mismatch, rather than reaching the
     * index as a runtime surprise. Unsupported on a load-only store.
     */
    void add(String id, Embedder.Embedding embedding, String payload);

    /**
     * Hybrid search: fuse the KNN vector ranking and the BM25 lexical ranking of {@code query} and
     * return up to {@code k} hits, highest fused score first, each carrying its stable ID and the
     * payload it was added with. The fusion strategy is a backend detail and never reaches here.
     */
    List<Hit> search(Embedder.Query query, int k);

    @Override
    void close();

    /** A scored search result: the document's stable ID, its round-tripped payload, and a fused score. */
    record Hit(String id, String payload, double score) {}
}
