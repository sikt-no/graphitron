package no.sikt.graphitron.mcp.rag.docs;

import no.sikt.graphitron.mcp.rag.EmbeddingStore;

/**
 * The warmed docs-retrieval handle (R385): the in-memory {@link EmbeddingStore} rebuilt from the
 * bundled tuples, paired with the {@code dimension} the bundle was embedded at.
 *
 * <p>The dimension travels with the store because the {@code docs.search} dimension guard reconciles
 * it against the runtime embedder's {@link no.sikt.graphitron.mcp.rag.Embedder#dimension()} (the
 * source of truth) to catch a build/runtime model-version skew before it surfaces as an opaque Lucene
 * KNN width error. The R372 store seam intentionally does not expose its width, so the bundled value
 * rides here, alongside the store it sized, rather than being re-read from the resource on every
 * query. The store still loads embedder-free (the dimension comes from the bundle header), exactly the
 * load path {@code AsyncWarm} documents.
 */
public record DocsIndex(EmbeddingStore store, int dimension) implements AutoCloseable {

    @Override
    public void close() {
        store.close();
    }
}
