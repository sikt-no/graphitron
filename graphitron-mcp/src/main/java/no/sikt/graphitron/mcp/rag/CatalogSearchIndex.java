package no.sikt.graphitron.mcp.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The warm-managed semantic index behind {@code catalog.search}. Owns the content-hash-keyed
 * Lucene index over the {@link CorpusTable} descriptors, persisted under
 * {@link RagConfig#cacheDir()} so a large catalog is not re-embedded on every {@code dev} restart.
 *
 * <p>Refresh is lazy self-observation: the index is a pure derived function of the corpus, and
 * every {@link #search} re-reads it and gates on its content hash (see {@link #observe()}), so
 * only a genuine content change kicks a re-embed. The re-embed runs on an {@link AsyncWarm}
 * background daemon, off the calling thread; while it runs the warm is {@link WarmState.Warming}
 * and searches report the warming degradation. The prior store stays open until {@link #close()} so
 * a swap never leaves a gap.
 *
 * <p>One gate rather than two. The corpus was a reference to a projection the build swapped, so a
 * cheap reference-identity check could skip composing at all; it is a pair of catalog queries now,
 * which yields fresh rows on every read and leaves no reference to compare. The content hash is what
 * the invalidation always rested on anyway, and what the gate now saves against is embedding text
 * rather than reading two indexed relations.
 *
 * <p>A persisted index is valid only for its corpus hash and the embedder that built it. Distinct
 * embedding models can share a dimension, so dimension alone cannot tell their indexes apart, and
 * loading one model's index under another is a silent correctness trap. An embedder-identity
 * manifest ({@code getClass().getName()} + {@code dimension()}) is written beside each index; the
 * loader rejects (rebuilds) any index whose recorded identity differs from the live embedder.
 */
public final class CatalogSearchIndex implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogSearchIndex.class);

    /** The {@code catalog/} sub-tree under the cache root; each {@code <corpusHash>/} index lives here. */
    private static final String CATALOG_SUBDIR = "catalog";

    /** The Lucene index lives under {@code <hash>/index/}; the manifest sits beside it, not inside it. */
    private static final String INDEX_SUBDIR = "index";
    private static final String MANIFEST_FILE = "embedder.manifest";

    /** Sibling-dir reaping keeps the current hash plus this many recent priors. */
    private static final int PRIORS_TO_KEEP = 1;

    private final Supplier<List<CorpusTable>> corpus;
    private final AsyncWarm<Embedder> embedderWarm;
    private final RagConfig config;

    private final Object lock = new Object();

    /** The hash of the live (or in-flight) index's corpus; the whole of the invalidation key. */
    private String liveHash;
    /** The warm whose state {@link #search} reads; null until the first observe kicks one. */
    private AsyncWarm<EmbeddingStore> liveWarm;
    /** Every store ever built, closed together on {@link #close()} (commits writers, frees readers). */
    private final List<EmbeddingStore> tracked = new ArrayList<>();

    /**
     * @param corpus       the catalog census read afresh on every observation, which is what makes a
     *                     capture since the last search visible without any refresh path. Called on
     *                     the calling thread, never on the warm daemon: the daemon embeds strings it
     *                     was handed, and reading the store on it would put a query behind a model
     *                     load
     * @param embedderWarm the shared embedder warm; started by the caller, this index
     *                     only {@link AsyncWarm#await() awaits} it before embedding
     * @param config       where to persist the content-hash-keyed index
     */
    public CatalogSearchIndex(
        Supplier<List<CorpusTable>> corpus, AsyncWarm<Embedder> embedderWarm, RagConfig config
    ) {
        this.corpus = corpus;
        this.embedderWarm = embedderWarm;
        this.config = config;
    }

    /**
     * Eager warm (bind-sync / warm-async): kick the initial index build from the current census,
     * off the calling thread. The shared embedder warm is started by the caller; the index warm only
     * awaits it. Production calls this at {@code dev} startup; a server that never calls it warms
     * lazily on the first {@link #search}.
     */
    public void start() {
        observe();
    }

    /**
     * Run the natural-language {@code query} against the live index, returning up to {@code limit}
     * ranked hits, or a degradation when the index is still warming or has failed. Observes the
     * corpus first, so a capture since the last call is picked up here.
     */
    public SearchOutcome search(String query, int limit) {
        AsyncWarm<EmbeddingStore> warm = observe();
        return switch (warm.state()) {
            case WarmState.Ready<EmbeddingStore> ready -> searchReady(ready.handle(), query, limit);
            case WarmState.Warming<EmbeddingStore> warming -> degraded(warming, "warming");
            case WarmState.Failed<EmbeddingStore> failed -> degraded(failed, "failed");
        };
    }

    /**
     * Blocks until the live index warm reaches a terminal state and returns it. A test / startup
     * affordance for deterministically waiting out the background build; production reads
     * {@link #search} instead and reports the degradation while warming.
     */
    public WarmState<EmbeddingStore> awaitWarm() {
        return observe().await();
    }

    private SearchOutcome searchReady(EmbeddingStore store, String query, int limit) {
        // A Ready index implies a Ready embedder (the build awaited it), but read it through the same
        // exhaustive switch rather than assuming, so an impossible state degrades instead of throwing.
        return switch (embedderWarm.state()) {
            case WarmState.Ready<Embedder> ready ->
                new SearchOutcome.Hits(store.search(ready.handle().embedQuery(query), limit));
            case WarmState.Warming<Embedder> warming -> degraded(warming, "warming");
            case WarmState.Failed<Embedder> failed -> degraded(failed, "failed");
        };
    }

    private static SearchOutcome degraded(WarmState<?> state, String status) {
        return new SearchOutcome.Degraded(WarmState.degradationMessage(state), status);
    }

    /**
     * Reads the corpus, composes it, and kicks a re-embed when its hash moved. Returns the live warm.
     *
     * <p>The read and the composition happen under the lock, so two searches arriving together
     * observe once rather than racing to kick two warms for the same corpus.
     */
    private AsyncWarm<EmbeddingStore> observe() {
        synchronized (lock) {
            var entries = composeCorpus(corpus.get());
            var descriptors = entries.stream().map(Entry::descriptor).toList();
            String hash = CatalogDescriptors.corpusHash(descriptors);
            if (liveWarm != null && hash.equals(liveHash)) {
                return liveWarm; // an unchanged census re-embeds nothing
            }
            liveHash = hash;
            liveWarm = kick(entries, descriptors, hash);
            return liveWarm;
        }
    }

    /** Kicks a fresh index warm for {@code hash}; the shared embedder warm is started by the caller. Caller holds {@link #lock}. */
    private AsyncWarm<EmbeddingStore> kick(List<Entry> entries, List<String> descriptors, String hash) {
        var warm = new AsyncWarm<EmbeddingStore>("catalog-index", () -> {
            EmbeddingStore store = buildOrLoad(entries, descriptors, hash);
            synchronized (lock) {
                tracked.add(store);
            }
            return store;
        });
        warm.start();
        return warm;
    }

    /**
     * The re-embed callable, run on the warm daemon: await the embedder, then load the persisted
     * index for this hash when its directory exists and its manifest matches the live embedder, or
     * rebuild it from scratch (embed the descriptors, persist, write the manifest). Reaps stale
     * sibling hash directories on the way out, keeping the current plus one prior.
     */
    private EmbeddingStore buildOrLoad(List<Entry> entries, List<String> descriptors, String hash)
        throws IOException {
        Embedder embedder = awaitEmbedder();
        int dimension = embedder.dimension();

        Path base = config.cacheDir().resolve(CATALOG_SUBDIR);
        Path entryDir = base.resolve(hash);
        Path indexDir = entryDir.resolve(INDEX_SUBDIR);
        Path manifest = entryDir.resolve(MANIFEST_FILE);

        if (Files.isDirectory(indexDir) && manifestMatches(manifest, embedder)) {
            reapSiblings(base, hash);
            return LuceneEmbeddingStore.load(indexDir, dimension);
        }

        // First build for this hash, or a stale / identity-mismatched directory: rebuild from scratch.
        deleteRecursively(entryDir);
        Files.createDirectories(indexDir);
        try (var building = LuceneEmbeddingStore.building(indexDir, dimension)) {
            var embeddings = embedder.embedDocuments(descriptors);
            for (int i = 0; i < entries.size(); i++) {
                building.add(entries.get(i).id(), embeddings.get(i), entries.get(i).payload());
            }
        } // close() commits the segments so the load below (and a later run) reads a complete index
        writeManifest(manifest, embedder);
        reapSiblings(base, hash);
        return LuceneEmbeddingStore.load(indexDir, dimension);
    }

    /** Awaits the shared embedder warm, mapping its terminal {@code Failed} into a build failure. */
    private Embedder awaitEmbedder() {
        return switch (embedderWarm.await()) {
            case WarmState.Ready<Embedder> ready -> ready.handle();
            case WarmState.Failed<Embedder> failed ->
                throw new IllegalStateException("embedder warm failed; catalog index cannot build", failed.cause());
            case WarmState.Warming<Embedder> ignored ->
                throw new IllegalStateException("embedder await returned Warming (impossible)");
        };
    }

    @Override
    public void close() {
        List<EmbeddingStore> toClose;
        synchronized (lock) {
            toClose = List.copyOf(tracked);
            tracked.clear();
        }
        for (var store : toClose) {
            try {
                store.close();
            } catch (RuntimeException e) {
                LOGGER.warn("graphitron:dev: error closing catalog search index store: {}", e.getMessage());
            }
        }
    }

    // ---- corpus composition ----

    /**
     * Composes one entry per table, in the order the census read returned them, which is the order
     * {@link CatalogDescriptors#corpusHash} digests: the ordering is part of the corpus identity, so
     * a stated table order is what keeps an unchanged catalog hashing to the same index.
     */
    private static List<Entry> composeCorpus(List<CorpusTable> tables) {
        var entries = new ArrayList<Entry>(tables.size());
        for (var table : tables) {
            entries.add(new Entry(
                table.id(),
                CatalogDescriptors.descriptor(table),
                table.comment() == null ? "" : table.comment()));
        }
        return entries;
    }

    /** One table's index entry: its stable {@code schema.table} id, its descriptor text, and its comment payload. */
    private record Entry(String id, String descriptor, String payload) {}

    // ---- embedder-identity manifest ----

    private static boolean manifestMatches(Path manifest, Embedder embedder) {
        if (!Files.isRegularFile(manifest)) {
            return false;
        }
        try {
            var lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            return lines.size() >= 2
                && lines.get(0).equals(embedder.getClass().getName())
                && lines.get(1).equals(Integer.toString(embedder.dimension()));
        } catch (IOException e) {
            return false; // an unreadable manifest is treated as a miss: rebuild rather than trust it
        }
    }

    private static void writeManifest(Path manifest, Embedder embedder) throws IOException {
        Files.write(manifest,
            List.of(embedder.getClass().getName(), Integer.toString(embedder.dimension())),
            StandardCharsets.UTF_8);
    }

    // ---- persistence reaping ----

    /** Deletes sibling {@code <hash>/} directories under {@code base}, keeping {@code currentHash} plus one prior. */
    private static void reapSiblings(Path base, String currentHash) throws IOException {
        if (!Files.isDirectory(base)) {
            return;
        }
        List<Path> priors;
        try (var stream = Files.list(base)) {
            priors = stream
                .filter(Files::isDirectory)
                .filter(p -> !p.getFileName().toString().equals(currentHash))
                .sorted(Comparator.comparing(CatalogSearchIndex::lastModified).reversed())
                .toList();
        }
        for (int i = PRIORS_TO_KEEP; i < priors.size(); i++) {
            deleteRecursively(priors.get(i));
        }
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0L);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to delete " + p, e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * The outcome of a {@link #search}: ranked hits, or a degradation message plus a machine-readable
     * status when the index is warming or failed. Sealed so the tool handler maps both arms
     * exhaustively onto the wire shape.
     */
    public sealed interface SearchOutcome {

        /** Ranked hits from a {@link WarmState.Ready} index, each carrying its stable id, payload, and fused score. */
        record Hits(List<EmbeddingStore.Hit> hits) implements SearchOutcome {}

        /** The index is not ready: {@code message} is the shared degradation text, {@code status} is {@code warming} / {@code failed}. */
        record Degraded(String message, String status) implements SearchOutcome {}
    }
}
