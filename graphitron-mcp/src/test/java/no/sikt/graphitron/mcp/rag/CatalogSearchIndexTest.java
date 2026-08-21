package no.sikt.graphitron.mcp.rag;

import no.sikt.graphitron.mcp.StoreFixture;
import no.sikt.graphitron.model.boot.StoreReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static no.sikt.graphitron.model.test.StoreAnswers.answered;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier: the silent-staleness and lifecycle invariants over the {@link FakeEmbedder} seam fake and
 * a {@link LuceneEmbeddingStore} {@code FSDirectory}, so they pin deterministically without ONNX.
 * Covers hash-covers-the-corpus re-embed gating, the warming-on-change re-entry, embedder-identity
 * rejection, the persistence round-trip plus sibling reaping, and the cross-warm failure propagation.
 *
 * <p>The corpus is a real capture rather than rows a test wrote, which is what lets the gate cases say
 * what they mean: an unchanged recapture is a recapture, and a changed catalog is a graph whose census
 * genuinely changed. {@link StoreFixture} pays a schema parse and a catalog walk for that, not a
 * generator run.
 *
 * <p>Three distinct censuses are available and the reaping case needs all three: the single-schema
 * generated model, the two-schema one, and no catalog at all, which is the pre-codegen state and hashes
 * as the empty corpus it is.
 */
class CatalogSearchIndexTest {

    // ---- the hash gate: a changed catalog re-embeds, an unchanged recapture does not ----

    @Test
    void aChangedCatalogReEmbedsButAnUnchangedRecaptureDoesNot(@TempDir Path tmp) throws Exception {
        var embedder = new SpyEmbedder(4);
        try (var fixture = StoreFixture.ofCatalog(tmp);
             var index = newIndex(fixture.reader(), embedder, tempCache())) {
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            // The strings handed to embedDocuments are exactly the composer's output over the census:
            // the hashed thing and the embedded thing are the same artifact, so they cannot drift.
            assertThat(embedder.lastTexts).isEqualTo(composed(fixture.reader()));
            assertThat(embedder.embedCalls).hasValue(1);

            // A recapture of the same generated model: every row rewritten, nothing changed. The read
            // composes afresh each time and the hash is what decides, so no observation re-embeds.
            fixture.recaptureCatalog(StoreFixture.JOOQ_PACKAGE);
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            assertThat(embedder.embedCalls).as("an unchanged census re-embeds nothing").hasValue(1);

            // A recapture against a different generated model: the graph's source membership is
            // rewritten, so the census its scope sees is another catalog and every descriptor changes.
            fixture.recaptureCatalog(StoreFixture.MULTISCHEMA_JOOQ_PACKAGE);
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            assertThat(embedder.embedCalls).as("a changed census re-embeds").hasValue(2);
            assertThat(embedder.lastTexts).isEqualTo(composed(fixture.reader()));
        }
    }

    // ---- warming-on-change re-entry: a build in flight serves the degradation message ----

    @Test
    void searchDuringARebuildReportsWarmingThenServesTheReadyIndex(@TempDir Path tmp) throws Exception {
        var embedder = new BlockingEmbedder(4);
        try (var fixture = StoreFixture.ofCatalog(tmp);
             var index = newIndex(fixture.reader(), embedder, tempCache())) {
            index.start(); // kicks the warm; embedDocuments blocks on the gate

            // The first search lands while the index warm is still Warming: degradation, not hits.
            var warming = index.search("film", 10);
            assertThat(warming).isInstanceOf(CatalogSearchIndex.SearchOutcome.Degraded.class);
            assertThat(((CatalogSearchIndex.SearchOutcome.Degraded) warming).status()).isEqualTo("warming");

            embedder.release();
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);

            // A Ready index answers, and what it answers with are census ids. Which of them ranks
            // highest is the embedder's business and not this case's: the fake vectors carry no
            // semantics, so the ranking here is BM25's alone and CatalogSearchOnnxTest owns retrieval.
            var hits = index.search("film", 10);
            assertThat(hits).isInstanceOf(CatalogSearchIndex.SearchOutcome.Hits.class);
            assertThat(((CatalogSearchIndex.SearchOutcome.Hits) hits).hits())
                .isNotEmpty()
                .extracting(EmbeddingStore.Hit::id)
                .allMatch(corpusIds(fixture.reader())::contains);
        }
    }

    // ---- embedder-identity rejection: an index built under one identity is rebuilt under another ----

    @Test
    void persistedIndexIsRejectedUnderADifferentEmbedderIdentityAndAcceptedUnderTheSame(
        @TempDir Path tmp
    ) throws Exception {
        Path cache = tempCache();
        try (var fixture = StoreFixture.ofCatalog(tmp)) {
            StoreReader reader = fixture.reader();

            // Build and persist under the FakeEmbedder identity.
            try (var first = newIndex(reader, new FakeEmbedder(4), cache)) {
                assertThat(first.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            }

            // Re-open the same cache under a *different* embedder class: identity mismatch -> rebuild.
            var rejecting = new SpyEmbedder(4);
            try (var second = newIndex(reader, rejecting, cache)) {
                assertThat(second.awaitWarm()).isInstanceOf(WarmState.Ready.class);
                assertThat(rejecting.embedCalls).as("a mismatched identity forces a rebuild").hasValue(1);
            }

            // Re-open again under the same SpyEmbedder identity: the manifest matches -> load, no re-embed.
            var accepting = new SpyEmbedder(4);
            try (var third = newIndex(reader, accepting, cache)) {
                assertThat(third.awaitWarm()).isInstanceOf(WarmState.Ready.class);
                assertThat(accepting.embedCalls).as("a matching identity loads without re-embedding").hasValue(0);
            }
        }
    }

    // ---- persistence round-trip + sibling reaping ----

    @Test
    void aPersistedIndexLoadsWithoutReEmbeddingAndReapingKeepsCurrentPlusOnePrior(
        @TempDir Path tmp
    ) throws Exception {
        Path cache = tempCache();
        try (var fixture = StoreFixture.ofCatalog(tmp)) {
            StoreReader reader = fixture.reader();

            try (var build = newIndex(reader, new SpyEmbedder(4), cache)) {
                assertThat(build.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            }

            // A fresh index over the same cache and the same census loads the prebuilt index: no
            // embedDocuments call, and the persisted index still answers.
            var reloader = new SpyEmbedder(4);
            try (var load = newIndex(reader, reloader, cache)) {
                assertThat(load.awaitWarm()).isInstanceOf(WarmState.Ready.class);
                assertThat(reloader.embedCalls).as("a prebuilt index is loaded, not re-embedded").hasValue(0);
                var hits = load.search("film", 10);
                assertThat(((CatalogSearchIndex.SearchOutcome.Hits) hits).hits())
                    .as("the loaded index answers off its persisted segments")
                    .isNotEmpty()
                    .extracting(EmbeddingStore.Hit::id)
                    .allMatch(corpusIds(reader)::contains);
            }

            // Three distinct censuses in one index lifetime: two generated models and the pre-codegen
            // state, whose empty corpus is an answer and hashes as one. Reaping keeps current plus one.
            try (var index = newIndex(reader, new SpyEmbedder(4), cache)) {
                assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
                fixture.recaptureCatalog(StoreFixture.MULTISCHEMA_JOOQ_PACKAGE);
                assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
                fixture.recaptureCatalog(null);
                assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);
            }
        }
        try (var dirs = Files.list(cache.resolve("catalog"))) {
            assertThat(dirs.filter(Files::isDirectory).count())
                .as("reaping keeps the current corpus dir plus one prior").isEqualTo(2L);
        }
    }

    // ---- cross-warm failure propagation ----

    @Test
    void aFailedEmbedderWarmYieldsAFailedIndexAndAFailedSearch(@TempDir Path tmp) throws Exception {
        var embedderWarm = new AsyncWarm<Embedder>("embedder", () -> {
            throw new IllegalStateException("model load boom");
        });
        embedderWarm.start();
        try (var fixture = StoreFixture.ofCatalog(tmp);
             var index = new CatalogSearchIndex(
                 fixture.reader(), fixture.graphName(), embedderWarm, new RagConfig(tempCache()))) {
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Failed.class);
            var outcome = index.search("film", 10);
            assertThat(outcome).isInstanceOf(CatalogSearchIndex.SearchOutcome.Degraded.class);
            assertThat(((CatalogSearchIndex.SearchOutcome.Degraded) outcome).status()).isEqualTo("failed");
        }
    }

    // ---- helpers ----

    private static CatalogSearchIndex newIndex(StoreReader reader, Embedder embedder, Path cache) {
        // The shared embedder warm is started by the caller (the server / DevMojo in production); the
        // index only awaits it, so the test must start it or the index warm blocks forever.
        var embedderWarm = new AsyncWarm<Embedder>("embedder", () -> embedder);
        embedderWarm.start();
        return new CatalogSearchIndex(reader, StoreFixture.GRAPH, embedderWarm, new RagConfig(cache));
    }

    private static Path tempCache() throws Exception {
        return Files.createTempDirectory("catalog-search-index-test");
    }

    /** The ids the census holds, for the cases whose claim is that an answer comes from it. */
    private static List<String> corpusIds(StoreReader reader) {
        return answered(CatalogCorpus.read(reader, StoreFixture.GRAPH)).stream()
            .map(CorpusTable::id).toList();
    }

    /** The corpus as the index composes it, read back through the same query the index reads. */
    private static List<String> composed(StoreReader reader) {
        return answered(CatalogCorpus.read(reader, StoreFixture.GRAPH)).stream()
            .map(CatalogDescriptors::descriptor)
            .toList();
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
