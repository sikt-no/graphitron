package no.sikt.graphitron.mcp.rag.docs;

import no.sikt.graphitron.mcp.rag.AsyncWarm;
import no.sikt.graphitron.mcp.rag.BgeEmbedder;
import no.sikt.graphitron.mcp.rag.Embedder;
import no.sikt.graphitron.mcp.rag.LuceneEmbeddingStore;

import java.io.InputStream;

/**
 * Stands up the two async warms the docs-retrieval tool rides, the first place to wire the
 * {@link AsyncWarm} warm lifecycle into the running server.
 *
 * <ul>
 *   <li>A shared {@code AsyncWarm<Embedder>} that loads {@link BgeEmbedder} off the dev thread (the
 *       heavy ONNX load). Shared because the catalog tool reuses the same embedder rather than
 *       loading a second copy.</li>
 *   <li>An {@code AsyncWarm<DocsIndex>} whose loader reads the bundled tuples off the classpath and
 *       rebuilds the in-memory store. It does <em>not</em> await the embedder: it loads a prebuilt
 *       vector set, and the store's width comes from the bundle header, so the store is sized without
 *       the embedder. The embedder is needed only at query time.</li>
 * </ul>
 *
 * <p>Both warms are created here but {@code start()}-ed and owned by the caller (the server's host,
 * {@code DevMojo}), mirroring how the live {@code Workspace} is threaded in. A RAG warm failure leaves
 * the server structured-only and never blocks the bind, a cross-cutting principle of the MCP server.
 */
public final class DocsRag {

    private DocsRag() {}

    /**
     * Classpath location of the pre-embedded bundle, written under {@code target/classes} by
     * {@link DocsIndexBuilder} at build time and packaged into the jar. Absent when the build-time
     * embed has not run (an IDE run off un-generated classes); the warm then fails and the server
     * stays structured-only.
     */
    public static final String BUNDLE_RESOURCE = "/mcp/docs-index/docs.bundle";

    /** A fresh, un-started shared embedder warm. The caller {@code start()}s it once and shares it. */
    public static AsyncWarm<Embedder> embedderWarm() {
        return new AsyncWarm<>("embedder", BgeEmbedder::new);
    }

    /** A fresh, un-started docs-index warm reading the bundled resource. The caller {@code start()}s it. */
    public static AsyncWarm<DocsIndex> docsWarm() {
        return new AsyncWarm<>("docs-index", DocsRag::loadDocsIndex);
    }

    /**
     * Reads the bundled tuples off the classpath and rebuilds the in-memory store by re-{@code add()}ing
     * the precomputed {@code (embedText, vector)} pairs under their stable ids and display payloads.
     * The one warm cost this pays is the HNSW graph rebuild over the (tiny) chunk set; nothing is
     * re-embedded. A missing resource is a load failure (the server degrades to structured-only).
     */
    public static DocsIndex loadDocsIndex() {
        try (InputStream in = DocsRag.class.getResourceAsStream(BUNDLE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "docs index bundle missing on the classpath at " + BUNDLE_RESOURCE
                        + " (build-time embed did not run; the server stays structured-only)");
            }
            return loadDocsIndex(in);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("failed to load docs index bundle", ex);
        }
    }

    /**
     * The reconstruction loader, factored out of the classpath read so the production warm path is
     * exercisable against a controlled bundle stream. Reads the tuples and rebuilds the in-memory
     * store; re-embeds nothing (the vectors are build-time).
     */
    public static DocsIndex loadDocsIndex(InputStream in) {
        DocsBundle.Loaded loaded = DocsBundle.read(in);
        var store = LuceneEmbeddingStore.inMemory(loaded.dimension());
        for (DocsBundle.Entry e : loaded.entries()) {
            store.add(e.id(), new Embedder.Embedding(e.embedText(), e.vector()), e.payload());
        }
        return new DocsIndex(store, loaded.dimension());
    }
}
