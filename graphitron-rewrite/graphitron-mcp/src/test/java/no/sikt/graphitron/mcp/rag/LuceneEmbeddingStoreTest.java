package no.sikt.graphitron.mcp.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Seam-tier (R372): the real {@link LuceneEmbeddingStore} over a RAM directory (the seam's fake),
 * driven through the {@link EmbeddingStore} seam with planted vectors so no ONNX is needed. Covers
 * the KNN round-trip, the BM25 hybrid surfacing a lexical match, and the dimension guard.
 */
class LuceneEmbeddingStoreTest {

    private static Embedder.Embedding doc(String text, float... vector) {
        return new Embedder.Embedding(text, vector);
    }

    @Test
    void searchReturnsTheNearestByKnnCarryingItsStableIdAndPayload() {
        try (var store = LuceneEmbeddingStore.inMemory(3)) {
            store.add("a", doc("apple", 1.0f, 0.0f, 0.0f), "payload-a");
            store.add("b", doc("banana", 0.0f, 1.0f, 0.0f), "payload-b");
            store.add("c", doc("carrot", 0.0f, 0.0f, 1.0f), "payload-c");

            // A query vector closest to "b" with no lexical overlap: KNN must put b first.
            var hits = store.search(new Embedder.Query("", new float[] {0.1f, 0.9f, 0.0f}), 3);

            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).id()).isEqualTo("b");
            assertThat(hits.get(0).payload()).isEqualTo("payload-b");
            assertThat(hits.get(0).score()).isGreaterThan(0.0);
        }
    }

    @Test
    void bm25HybridSurfacesALexicalMatchThatKnnTopKAloneWouldMiss() {
        try (var store = LuceneEmbeddingStore.inMemory(3)) {
            // Three documents cluster near the query vector; only the far one is a lexical match.
            store.add("near1", doc("alpha", 1.0f, 0.0f, 0.0f), "p1");
            store.add("near2", doc("beta", 0.9f, 0.1f, 0.0f), "p2");
            store.add("near3", doc("gamma", 0.8f, 0.2f, 0.0f), "p3");
            store.add("lexical", doc("carrot vegetable", 0.0f, 0.0f, 1.0f), "p-lex");

            // Query vector hugs the cluster (KNN would never reach "lexical" in the top 2), but the
            // query text matches "lexical" alone. RRF must pull the lexical match into the top 2.
            var hits = store.search(new Embedder.Query("carrot", new float[] {1.0f, 0.0f, 0.0f}), 2);

            assertThat(hits).hasSize(2);
            assertThat(hits).extracting(EmbeddingStore.Hit::id).contains("lexical");
        }
    }

    @Test
    void addRejectsAVectorWhoseWidthDisagreesWithTheConfiguredDimension() {
        try (var store = LuceneEmbeddingStore.inMemory(3)) {
            assertThatThrownBy(() ->
                store.add("wrong", doc("oops", 1.0f, 0.0f, 0.0f, 0.0f), "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width 4")
                .hasMessageContaining("dimension 3");
        }
    }

    @Test
    void loadOnlyStoreRejectsAdd() {
        // A load-only store has no writer; add is not part of its contract.
        try (var store = LuceneEmbeddingStore.inMemory(3)) {
            store.add("a", doc("apple", 1.0f, 0.0f, 0.0f), "p");
            // (in-memory writable store proves add works; the load-only rejection is covered below)
        }
        assertThatThrownBy(() -> {
            // Build an empty index to a temp dir, then reopen it read-only and try to add.
            var dir = java.nio.file.Files.createTempDirectory("rag-load-only");
            try (var building = LuceneEmbeddingStore.building(dir, 3)) {
                building.add("seed", doc("seed", 1.0f, 0.0f, 0.0f), "p");
            }
            try (var loaded = LuceneEmbeddingStore.load(dir, 3)) {
                loaded.add("nope", doc("nope", 0.0f, 1.0f, 0.0f), "p");
            }
        }).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aLoadedStoreSearchesThePrebuiltIndex() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("rag-load-search");
        try (var building = LuceneEmbeddingStore.building(dir, 3)) {
            building.add("a", doc("apple", 1.0f, 0.0f, 0.0f), "payload-a");
            building.add("b", doc("banana", 0.0f, 1.0f, 0.0f), "payload-b");
        }
        try (var loaded = LuceneEmbeddingStore.load(dir, 3)) {
            var hits = loaded.search(new Embedder.Query("", new float[] {0.9f, 0.1f, 0.0f}), 2);
            assertThat(hits).extracting(EmbeddingStore.Hit::id).contains("a");
            assertThat(hits).filteredOn(h -> h.id().equals("a"))
                .singleElement()
                .extracting(EmbeddingStore.Hit::payload).isEqualTo("payload-a");
        }
    }
}
