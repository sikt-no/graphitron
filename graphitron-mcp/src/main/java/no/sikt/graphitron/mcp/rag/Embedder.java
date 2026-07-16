package no.sikt.graphitron.mcp.rag;

import java.util.List;

/**
 * The graphitron-owned embedding seam. A thin wrapper over whatever embedding model
 * backs it, published so consumers (the semantic tools) never know the model
 * is bge or that bge is asymmetric.
 *
 * <p><strong>Query / document asymmetry lives here, not in the caller.</strong> bge applies an
 * instruction prefix on the query side only; {@link #embedQuery} prepends it internally,
 * {@link #embedDocuments} does not. A model with no such asymmetry is absorbed by making its
 * adapter's {@code embedQuery} delegate to the same path as {@code embedDocuments}; either way the
 * branch is decided once, in the seam, rather than re-derived at every call site. This is the
 * "if two consumers evaluate the same predicate over a field, the branch belongs in the model"
 * rule applied to the embedder.
 *
 * <p><strong>A raw {@code float[]} never crosses a seam, and a vector never travels apart from the
 * text it was embedded from.</strong> The two methods return {@link Query} / {@link Embedding}
 * records, and this interface is their sole producer; both bundle the BM25 text with its KNN
 * vector, so a hybrid store cannot fuse a vector from one string against the BM25 text of another.
 * The records name no backing-library type, so the swap guarantee (a deferred multilingual model)
 * attaches to this wrapper, not to the library underneath.
 */
public interface Embedder {

    /**
     * Embed a search query, applying the model's query-side instruction (the bge prefix) to the
     * vector only. The returned {@link Query} carries the original, unprefixed text for BM25
     * alongside the prefixed-and-embedded vector for KNN.
     */
    Query embedQuery(String text);

    /**
     * Embed documents with no query-side instruction applied. The returned list aligns positionally
     * with {@code texts}; each {@link Embedding} carries its source text alongside its vector.
     */
    List<Embedding> embedDocuments(List<String> texts);

    /** The vector width this embedder produces: the single source of truth a store is sized against. */
    int dimension();

    /**
     * A document: its BM25 text paired with its KNN vector. Produced only by
     * {@link #embedDocuments}; the pairing is carried by the type so neither half can drift from the
     * other across the store seam.
     */
    record Embedding(String text, float[] vector) {}

    /**
     * A query: its BM25 text paired with its KNN vector. Produced only by {@link #embedQuery}. The
     * text is the caller's original query (no instruction prefix); the vector is embedded from the
     * prefixed form, so lexical matching sees the user's words while semantic matching sees the
     * instruction-conditioned query bge expects.
     */
    record Query(String text, float[] vector) {}
}
