package no.sikt.graphitron.mcp.rag;

import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
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
 * The warm-managed, self-observing semantic index behind {@code catalog.search}. Owns the
 * content-hash-keyed Lucene index over the {@link CatalogFacts} descriptors, persisted under
 * {@link RagConfig#cacheDir()} so a large catalog is not re-embedded on every {@code dev} restart.
 *
 * <p><strong>Refresh: lazy self-observe, no new dev trigger.</strong> The index is a pure derived
 * function of the live {@link CatalogFacts}, so it mirrors the {@code ReverseEdgeIndex.Cache}: no
 * {@code BuildArtifacts} component, no {@code Workspace} field, no {@code DevMojo} listener. Each
 * {@link #search} reads the {@code Supplier<CatalogFacts>} (the live {@code volatile}
 * {@code Workspace.catalogFacts()}) through two gates:
 * <ol>
 *   <li><strong>Reference identity.</strong> Same reference the live index was built from -> serve
 *   immediately. A no-op recompile that swaps the reference but not the content falls through to
 *   gate 2; everything else short-circuits here.</li>
 *   <li><strong>Content hash.</strong> On a changed reference, compose the descriptor corpus (cheap
 *   string work) and hash it ({@link CatalogDescriptors#corpusHash(List)}). A hash equal to the live
 *   index's hash means the content is unchanged: rebind the held reference and serve the existing
 *   index. Only a changed hash kicks a re-embed.</li>
 * </ol>
 *
 * <p>The re-embed runs on an {@link AsyncWarm} background daemon, off the classpath-watcher thread,
 * so the live catalog swap is never stalled by re-embedding. A content change re-enters
 * {@link WarmState.Warming} (the existing sealed shape; no new "refreshing" state), so the first
 * search during a rebuild reports the warming degradation message; on completion the new index
 * becomes live. The prior store is kept open until {@link #close()} so a swap never leaves a gap.
 *
 * <p><strong>Validity = corpus hash + embedder identity.</strong> The persisted index is valid only
 * for the embedder that built it. bge-small-en and the deferred {@code multilingual-e5-small} swap
 * are both 384-dim, so dimension alone cannot tell them apart: loading one model's index
 * under another is a silent correctness trap. An embedder-identity manifest
 * ({@code getClass().getName()} + {@code dimension()}) is written beside each index; the loader
 * rejects (rebuilds) any index whose recorded identity differs from the live embedder.
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

    private final Supplier<CatalogFacts> facts;
    private final AsyncWarm<Embedder> embedderWarm;
    private final RagConfig config;

    private final Object lock = new Object();

    /** The reference the live corpus was composed from; the gate-1 identity key. */
    private CatalogFacts liveFactsRef;
    /** The hash of the live (or in-flight) index's corpus; the gate-2 content key. */
    private String liveHash;
    /** The warm whose state {@link #search} reads; null until the first observe kicks one. */
    private AsyncWarm<EmbeddingStore> liveWarm;
    /** Every store ever built, closed together on {@link #close()} (commits writers, frees readers). */
    private final List<EmbeddingStore> tracked = new ArrayList<>();

    /**
     * @param facts        the live catalog source, typically {@code workspace::catalogFacts}; read
     *                     by reference identity on every search so a build swap is observed
     * @param embedderWarm the shared embedder warm; started by the caller, this index
     *                     only {@link AsyncWarm#await() awaits} it before embedding
     * @param config       where to persist the content-hash-keyed index
     */
    public CatalogSearchIndex(Supplier<CatalogFacts> facts, AsyncWarm<Embedder> embedderWarm, RagConfig config) {
        this.facts = facts;
        this.embedderWarm = embedderWarm;
        this.config = config;
    }

    /**
     * Eager warm (bind-sync / warm-async): kick the initial index build from the current facts,
     * off the calling thread. The shared embedder warm is started by the caller; the index warm only
     * awaits it. Production calls this at {@code dev} startup; a server that never calls it warms
     * lazily on the first {@link #search}.
     */
    public void start() {
        observe();
    }

    /**
     * Run the natural-language {@code query} against the live index, returning up to {@code limit}
     * ranked hits, or a degradation when the index is still warming or has failed. Applies the
     * two-gate self-observe first, so a schema change since the last call is picked up here.
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

    /** Applies the two-gate self-observe, kicking a re-embed on a changed hash. Returns the live warm. */
    private AsyncWarm<EmbeddingStore> observe() {
        synchronized (lock) {
            CatalogFacts current = facts.get();
            if (liveWarm != null && current == liveFactsRef) {
                return liveWarm; // gate 1: reference identity, the cheap common path
            }
            var entries = composeCorpus(current);
            var descriptors = entries.stream().map(Entry::descriptor).toList();
            String hash = CatalogDescriptors.corpusHash(descriptors);
            liveFactsRef = current;
            if (liveWarm != null && hash.equals(liveHash)) {
                return liveWarm; // gate 2: same content under a new reference -> no re-embed
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

    private static List<Entry> composeCorpus(CatalogFacts facts) {
        var tables = facts.tablesByQualifiedName().values();
        var entries = new ArrayList<Entry>(tables.size());
        for (var table : tables) {
            entries.add(new Entry(
                table.qualifiedName(),
                CatalogDescriptors.descriptor(table),
                table.comment().orElse("")));
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
