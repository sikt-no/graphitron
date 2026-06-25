package no.sikt.graphitron.mcp.rag;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sole shipping {@link EmbeddingStore} backend (R372 D2): Lucene HNSW, with BM25 lexical search
 * and KNN vector search in a single index, fused by reciprocal-rank fusion. Pure Java, so the only
 * native footprint in the RAG stack is the embedder; this store ships no JNI.
 *
 * <p><strong>Hybrid fusion is reciprocal-rank fusion (RRF), a Lucene-internal detail behind the
 * seam.</strong> A {@link #search} runs the KNN vector query and the BM25 text query separately and
 * fuses their two ranked lists by {@code score = Σ 1/(RRF_K + rank)} over the documents each
 * surfaces, rather than normalizing and linearly combining raw KNN distances against BM25 scores.
 * RRF is parameter-light (one constant, no per-corpus calibration) and makes a "BM25 hybrid
 * surfaces a lexical match" outcome deterministic. The choice never reaches a consumer and can
 * change without touching the consuming tools.
 *
 * <p><strong>The dimension invariant is structural, checked once.</strong> The store is constructed
 * with the embedder's {@code dimension()} (the single source of truth for vector width); {@link #add}
 * validates each embedding's vector length against it at that one point and fails loudly on
 * mismatch, so the embedder that produces vectors and the index that stores them agree by
 * construction, not by prose. The store needs the embedder's width, not the embedder itself, so a
 * load-only index still loads off disk before the embedder is {@code Ready} (R372 D3).
 *
 * <p>Construction picks build vs load and never names the backend at the consumer: {@link #inMemory}
 * (RAM directory, the seam's test fake), {@link #building} (writable on-disk index), and
 * {@link #load} (read-only over a prebuilt index). The first two accept {@link #add}; {@link #load}
 * rejects it.
 */
public final class LuceneEmbeddingStore implements EmbeddingStore {

    /** Lucene field names. Internal to this backend; nothing outside this class names them. */
    private static final String ID_FIELD = "id";
    private static final String TEXT_FIELD = "text";
    private static final String VECTOR_FIELD = "vector";
    private static final String PAYLOAD_FIELD = "payload";

    /**
     * The RRF rank constant. 60 is the value the original RRF paper uses and the de-facto default;
     * it damps the contribution of low-ranked hits so a document near the top of either list scores
     * well without one list dominating. Fixed, not tuned per corpus, which is the point of RRF.
     */
    private static final int RRF_K = 60;

    private final Directory directory;
    private final Analyzer analyzer;
    private final int dimension;

    /** The writer for a build store; {@code null} for a load-only store, whose {@link #add} is rejected. */
    private final IndexWriter writer;

    private LuceneEmbeddingStore(Directory directory, int dimension, boolean writable) {
        this.directory = directory;
        this.analyzer = new StandardAnalyzer();
        this.dimension = dimension;
        try {
            this.writer = writable ? new IndexWriter(directory, new IndexWriterConfig(analyzer)) : null;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open Lucene index writer", e);
        }
    }

    /**
     * A writable, in-RAM store: real Lucene HNSW over a {@link ByteBuffersDirectory}. This is the
     * seam's fake, fast enough for unit tests; it ships nothing and persists nothing.
     */
    public static LuceneEmbeddingStore inMemory(int dimension) {
        return new LuceneEmbeddingStore(new ByteBuffersDirectory(), dimension, true);
    }

    /** A writable, on-disk store at {@code path}: the build path for a persisted index. */
    public static LuceneEmbeddingStore building(Path path, int dimension) {
        return new LuceneEmbeddingStore(openFs(path), dimension, true);
    }

    /** A read-only store over a prebuilt index at {@code path}. {@link #add} is unsupported. */
    public static LuceneEmbeddingStore load(Path path, int dimension) {
        return new LuceneEmbeddingStore(openFs(path), dimension, false);
    }

    private static Directory openFs(Path path) {
        try {
            return FSDirectory.open(path);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open Lucene index at " + path, e);
        }
    }

    @Override
    public void add(String id, Embedder.Embedding embedding, String payload) {
        if (writer == null) {
            throw new UnsupportedOperationException("this store is load-only; add is not supported");
        }
        float[] vector = embedding.vector();
        if (vector.length != dimension) {
            throw new IllegalArgumentException(
                "embedding vector width " + vector.length + " disagrees with store dimension "
                    + dimension + " (id=" + id + ")");
        }
        var doc = new Document();
        doc.add(new StringField(ID_FIELD, id, Field.Store.YES));
        doc.add(new TextField(TEXT_FIELD, embedding.text(), Field.Store.NO));
        doc.add(new KnnFloatVectorField(VECTOR_FIELD, vector, VectorSimilarityFunction.COSINE));
        doc.add(new StoredField(PAYLOAD_FIELD, payload));
        try {
            writer.addDocument(doc);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to add document to Lucene index (id=" + id + ")", e);
        }
    }

    @Override
    public List<EmbeddingStore.Hit> search(Embedder.Query query, int k) {
        try (DirectoryReader reader = openReader()) {
            var searcher = new IndexSearcher(reader);

            TopDocs knn = searcher.search(new KnnFloatVectorQuery(VECTOR_FIELD, query.vector(), k), k);
            TopDocs bm25 = searcher.search(bm25Query(query.text()), k);

            // RRF over the two ranked lists: a document's fused score sums 1/(RRF_K + rank) across
            // whichever lists surface it (rank is 1-based). The map preserves first-seen order only
            // for stable iteration; the final ordering is by fused score below.
            var fused = new LinkedHashMap<Integer, Double>();
            accumulate(fused, knn);
            accumulate(fused, bm25);

            var ranked = new ArrayList<>(fused.entrySet());
            ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            var storedFields = searcher.storedFields();
            var hits = new ArrayList<EmbeddingStore.Hit>(Math.min(k, ranked.size()));
            for (int i = 0; i < ranked.size() && i < k; i++) {
                var entry = ranked.get(i);
                Document doc = storedFields.document(entry.getKey());
                hits.add(new EmbeddingStore.Hit(doc.get(ID_FIELD), doc.get(PAYLOAD_FIELD), entry.getValue()));
            }
            return hits;
        } catch (IOException e) {
            throw new UncheckedIOException("Lucene search failed", e);
        }
    }

    /** Adds RRF contributions of one ranked list into the fused-score accumulator. */
    private static void accumulate(Map<Integer, Double> fused, TopDocs docs) {
        ScoreDoc[] scoreDocs = docs.scoreDocs;
        for (int rank = 0; rank < scoreDocs.length; rank++) {
            int docId = scoreDocs[rank].doc;
            fused.merge(docId, 1.0 / (RRF_K + rank + 1), Double::sum);
        }
    }

    /**
     * Builds the BM25 lexical query: the analyzer tokenizes {@code text} the same way the indexed
     * {@code text} field was, and each token becomes a SHOULD term clause. An empty / all-stopword
     * query yields a clause-less BooleanQuery (no lexical hits), leaving KNN to carry the search.
     */
    private Query bm25Query(String text) {
        var builder = new BooleanQuery.Builder();
        try (TokenStream tokens = analyzer.tokenStream(TEXT_FIELD, text)) {
            var term = tokens.addAttribute(CharTermAttribute.class);
            tokens.reset();
            while (tokens.incrementToken()) {
                builder.add(new TermQuery(new Term(TEXT_FIELD, term.toString())), BooleanClause.Occur.SHOULD);
            }
            tokens.end();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to analyze BM25 query text", e);
        }
        return builder.build();
    }

    /** A near-real-time reader for a build store (sees un-committed adds), or a plain reader for load-only. */
    private DirectoryReader openReader() throws IOException {
        return writer != null ? DirectoryReader.open(writer) : DirectoryReader.open(directory);
    }

    @Override
    public void close() {
        try {
            if (writer != null) {
                writer.close();
            }
            directory.close();
            analyzer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to close Lucene store", e);
        }
    }
}
