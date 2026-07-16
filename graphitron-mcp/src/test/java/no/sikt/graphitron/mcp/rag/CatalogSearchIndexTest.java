package no.sikt.graphitron.mcp.rag;

import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier: the three silent-staleness / lifecycle invariants over the {@link FakeEmbedder}
 * seam fake and a {@link LuceneEmbeddingStore} {@code FSDirectory}, so they pin deterministically
 * without ONNX. Covers hash-covers-the-corpus re-embed gating, the warming-on-change re-entry,
 * embedder-identity rejection, the persistence round-trip + sibling reaping, and the cross-warm
 * failure propagation.
 */
class CatalogSearchIndexTest {

    // ---- gate invariants: a changed hash re-embeds, a same-content reference change does not ----

    @Test
    void changedContentReEmbedsButSameContentReferenceSwapDoesNot() throws Exception {
        var facts = new AtomicReference<>(factsWith(table("film", "title")));
        var embedder = new SpyEmbedder(4);
        try (var index = newIndex(facts::get, embedder, tempCache())) {
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            // The strings handed to embedDocuments are exactly the composer's output: the hashed
            // thing and the embedded thing are the same artifact, so they cannot drift.
            assertThat(embedder.lastTexts).isEqualTo(composed(facts.get()));
            assertThat(embedder.embedCalls).hasValue(1);

            // A no-op recompile: a fresh CatalogFacts with identical content. Gate 1 misses on the
            // reference, gate 2 hits on the hash -> no re-embed.
            facts.set(factsWith(table("film", "title")));
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            assertThat(embedder.embedCalls).as("same content under a new reference re-embeds nothing").hasValue(1);

            // A same-reference call short-circuits on gate 1 (no compose, no re-embed).
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            assertThat(embedder.embedCalls).hasValue(1);

            // A changed column changes a descriptor, so the hash, so a re-embed kicks.
            facts.set(factsWith(table("film", "release_year")));
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            assertThat(embedder.embedCalls).as("a changed descriptor re-embeds").hasValue(2);
        }
    }

    // ---- warming-on-change re-entry: a build in flight serves the degradation message ----

    @Test
    void searchDuringARebuildReportsWarmingThenServesTheReadyIndex() throws Exception {
        var facts = new AtomicReference<>(factsWith(table("film", "title")));
        var embedder = new BlockingEmbedder(4);
        try (var index = newIndex(facts::get, embedder, tempCache())) {
            index.start(); // kicks the warm; embedDocuments blocks on the gate

            // The first search lands while the index warm is still Warming: degradation, not hits.
            var warming = index.search("film", 10);
            assertThat(warming).isInstanceOf(CatalogSearchIndex.SearchOutcome.Degraded.class);
            assertThat(((CatalogSearchIndex.SearchOutcome.Degraded) warming).status()).isEqualTo("warming");

            embedder.release();
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);

            var hits = index.search("film", 10);
            assertThat(hits).isInstanceOf(CatalogSearchIndex.SearchOutcome.Hits.class);
            assertThat(((CatalogSearchIndex.SearchOutcome.Hits) hits).hits())
                .extracting(EmbeddingStore.Hit::id).contains("public.film");
        }
    }

    // ---- embedder-identity rejection: an index built under one identity is rebuilt under another ----

    @Test
    void persistedIndexIsRejectedUnderADifferentEmbedderIdentityAndAcceptedUnderTheSame() throws Exception {
        Path cache = tempCache();
        var facts = factsWith(table("film", "title"));

        // Build and persist under the FakeEmbedder identity.
        try (var first = newIndex(() -> facts, new FakeEmbedder(4), cache)) {
            assertThat(first.awaitWarm()).isInstanceOf(WarmState.Ready.class);
        }

        // Re-open the same cache under a *different* embedder class: identity mismatch -> rebuild.
        var rejecting = new SpyEmbedder(4);
        try (var second = newIndex(() -> facts, rejecting, cache)) {
            assertThat(second.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            assertThat(rejecting.embedCalls).as("a mismatched identity forces a rebuild").hasValue(1);
        }

        // Re-open again under the same SpyEmbedder identity: the manifest matches -> load, no re-embed.
        var accepting = new SpyEmbedder(4);
        try (var third = newIndex(() -> facts, accepting, cache)) {
            assertThat(third.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            assertThat(accepting.embedCalls).as("a matching identity loads without re-embedding").hasValue(0);
        }
    }

    // ---- persistence round-trip + sibling reaping ----

    @Test
    void aPersistedIndexLoadsWithoutReEmbeddingAndReapingKeepsCurrentPlusOnePrior() throws Exception {
        Path cache = tempCache();
        var facts = factsWith(table("film", "title"));

        try (var build = newIndex(() -> facts, new SpyEmbedder(4), cache)) {
            assertThat(build.awaitWarm()).isInstanceOf(WarmState.Ready.class);
        }

        // A fresh index over the same cache loads the prebuilt index: no embedDocuments call.
        var reloader = new SpyEmbedder(4);
        try (var load = newIndex(() -> facts, reloader, cache)) {
            assertThat(load.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            assertThat(reloader.embedCalls).as("a prebuilt index is loaded, not re-embedded").hasValue(0);
            var hits = load.search("film", 10);
            assertThat(((CatalogSearchIndex.SearchOutcome.Hits) hits).hits())
                .extracting(EmbeddingStore.Hit::id).contains("public.film");
        }

        // Three distinct corpora in one index: reaping keeps the current hash dir plus one prior.
        var ref = new AtomicReference<>(factsWith(table("film", "title")));
        try (var index = newIndex(ref::get, new SpyEmbedder(4), cache)) {
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            ref.set(factsWith(table("actor", "name")));
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            ref.set(factsWith(table("language", "code")));
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
        }
        try (var dirs = Files.list(cache.resolve("catalog"))) {
            assertThat(dirs.filter(Files::isDirectory).count())
                .as("reaping keeps the current corpus dir plus one prior").isEqualTo(2L);
        }
    }

    // ---- cross-warm failure propagation ----

    @Test
    void aFailedEmbedderWarmYieldsAFailedIndexAndAFailedSearch() throws Exception {
        var facts = factsWith(table("film", "title"));
        var embedderWarm = new AsyncWarm<Embedder>("embedder", () -> {
            throw new IllegalStateException("model load boom");
        });
        embedderWarm.start();
        try (var index = new CatalogSearchIndex(() -> facts, embedderWarm, new RagConfig(tempCache()))) {
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Failed.class);
            var outcome = index.search("film", 10);
            assertThat(outcome).isInstanceOf(CatalogSearchIndex.SearchOutcome.Degraded.class);
            assertThat(((CatalogSearchIndex.SearchOutcome.Degraded) outcome).status()).isEqualTo("failed");
        }
    }

    // ---- helpers ----

    private static CatalogSearchIndex newIndex(
        java.util.function.Supplier<CatalogFacts> facts, Embedder embedder, Path cache
    ) {
        // The shared embedder warm is started by the caller (the server / DevMojo in production); the
        // index only awaits it, so the test must start it or the index warm blocks forever.
        var embedderWarm = new AsyncWarm<Embedder>("embedder", () -> embedder);
        embedderWarm.start();
        return new CatalogSearchIndex(facts, embedderWarm, new RagConfig(cache));
    }

    private static Path tempCache() throws Exception {
        return Files.createTempDirectory("catalog-search-index-test");
    }

    private static List<String> composed(CatalogFacts facts) {
        return facts.tablesByQualifiedName().values().stream()
            .map(CatalogDescriptors::descriptor)
            .toList();
    }

    private static CatalogFacts.Table table(String name, String column) {
        return new CatalogFacts.Table(
            "public", name, Optional.empty(),
            List.of(new CatalogFacts.Column(column, column.toUpperCase(), "varchar", false, Optional.empty())),
            Optional.empty(), List.of(), List.of(), CatalogFacts.ForeignKeys.empty());
    }

    private static CatalogFacts factsWith(CatalogFacts.Table table) {
        var map = new LinkedHashMap<String, CatalogFacts.Table>();
        map.put(table.qualifiedName(), table);
        return new CatalogFacts(map);
    }

    /** A counting {@link Embedder}: zero vectors (BM25 carries ranking), but records the embed calls and texts. */
    private static final class SpyEmbedder implements Embedder {
        private final int dimension;
        final AtomicInteger embedCalls = new AtomicInteger();
        volatile List<String> lastTexts = List.of();

        SpyEmbedder(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public Query embedQuery(String text) {
            return new Query(text, FakeEmbedder.oneHot(text, dimension));
        }

        @Override
        public List<Embedding> embedDocuments(List<String> texts) {
            embedCalls.incrementAndGet();
            lastTexts = List.copyOf(texts);
            return texts.stream().map(t -> new Embedding(t, FakeEmbedder.oneHot(t, dimension))).toList();
        }

        @Override
        public int dimension() {
            return dimension;
        }
    }

    /** A {@link SpyEmbedder} whose {@link #embedDocuments} blocks until {@link #release()} is called. */
    private static final class BlockingEmbedder implements Embedder {
        private final int dimension;
        private final CountDownLatch gate = new CountDownLatch(1);

        BlockingEmbedder(int dimension) {
            this.dimension = dimension;
        }

        void release() {
            gate.countDown();
        }

        @Override
        public Query embedQuery(String text) {
            return new Query(text, FakeEmbedder.oneHot(text, dimension));
        }

        @Override
        public List<Embedding> embedDocuments(List<String> texts) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return texts.stream().map(t -> new Embedding(t, FakeEmbedder.oneHot(t, dimension))).toList();
        }

        @Override
        public int dimension() {
            return dimension;
        }
    }
}
