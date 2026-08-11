package no.sikt.graphitron.rewrite.capture;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.derive.AuthoredClaimConflicts;
import no.sikt.graphitron.rewrite.derive.ClaimDomain;
import no.sikt.graphitron.rewrite.derive.ClaimDomainRows;
import no.sikt.graphitron.rewrite.derive.InputOccurrencePaths;
import no.sikt.graphitron.rewrite.derive.ReachabilityRows;
import no.sikt.graphitron.rewrite.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_EXTENSION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_INPUT;

/**
 * Entry point for the generator's capture loads: opens a fact store for the run and fills it from
 * the parsed SDL, the jOOQ catalog, and the consumer's compiled extension classes.
 *
 * <p>Both loads are infallible by construction, and construction is the only guarantee in play.
 * The {@link TypeDefinitionRegistry} validates nothing, so every capture path is tolerant: what
 * does not fit records raw and located rather than throwing. Capture is total, with no
 * reachability pruning; a primary-key violation on any base relation is therefore a capture bug,
 * never something an author's schema can provoke.
 *
 * <p>The store has its first reader: {@link #runWithDetections} runs the authored-claim
 * conflict rule ({@link AuthoredClaimConflicts}) over the freshly captured rows and returns its
 * typed {@link AuthoredClaimConflicts.Detection} product (the violations for the caller's error
 * stream, and the field-conflict claims the snapshot's {@code Conflicted} projection overlay
 * consumes), so what that detection reports is decided by the store's content. Every other relation is still populated beside the live pipeline and read by
 * nothing; consumers migrate onto it one at a time.
 *
 * <p>A run captures exactly one graph; the store may hold many. The persisted store is shared by
 * every module of a workspace, so a warm open reconciles only what this run owns, and any cache
 * trouble at all (a graph name another module's directory already holds, a concurrent writer the
 * store could not be shared with) falls back to a private in-memory store for this run and leaves
 * the file alone: warmth is the only thing a cache is ever allowed to cost.
 */
public final class FactCapture {

    private static final Logger LOG = LoggerFactory.getLogger(FactCapture.class);

    private FactCapture() {}

    /**
     * The graph a capture run writes under: the partition every SDL row of the run carries, the
     * base directory the graph's ownership is checked against, and the recipe capture remembers
     * beside it ({@code null} for a caller with no resolved {@code <schemaInputs>} configuration,
     * whose graph then records no recipe and is not replayable).
     */
    public record GraphIdentity(String name, Path baseDir, SchemaRecipe recipe) {
        public GraphIdentity {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(baseDir, "baseDir");
            if (name.isBlank()) {
                throw new IllegalArgumentException("graph name must be non-blank");
            }
            baseDir = baseDir.toAbsolutePath().normalize();
        }

        public GraphIdentity(String name, Path baseDir) {
            this(name, baseDir, null);
        }
    }

    /**
     * Runs both loads against the store for {@code storeDirectory} and closes it.
     *
     * <p>With a home the store is the shared file under it, so this run starts from the previous
     * runs' rows and rewrites only what it owns and cannot prove unchanged; without one it is a
     * private in-memory database that dies here, which is what every caller with no home to give
     * should get. The two differ in cost, never in content: a warm store is refreshed to exactly
     * the rows a cold load would have produced, and the agreement anchors are stated against both.
     *
     * <p>Two cache conditions demote a run to the in-memory store, and neither touches the file.
     * A graph name already recorded against a different base directory is not taken over, because
     * ownership-scoped refresh would otherwise let two checkouts thrash one partition silently;
     * this is the one cache condition a consumer can fix, so it is the one that always logs, naming
     * both directories and {@code <graphName>} as the remedy. And a write that fails against the
     * shared file is retried once against that same file before being demoted, which is what tells
     * a concurrency casualty (a concurrent writer of the same rows, a lock that timed out; cleared
     * by the time the retry runs, since capture's own delete-then-rewrite is safely rerunnable)
     * apart from a deterministic capture bug (the same failure both times, timing-independent).
     * The first case is absorbed at debug level, unremarkable by the time it is logged. The second
     * demotes to the in-memory store just the same, since a cache is never allowed to cost more
     * than warmth, but logs at warn with the exception, naming the graph, because a deterministic
     * failure means this graph's warm start is out for good until the underlying bug is fixed, and
     * that is not something the debug level below may leave unread.
     */
    public static void run(Path storeDirectory, GraphIdentity graph, TypeDefinitionRegistry registry,
                           JooqCatalog jooq, List<CompletionData.ExternalReference> extensions,
                           NodeDeclaration nodes) {
        runInternal(storeDirectory, graph, registry, jooq, extensions, nodes, null);
    }

    /**
     * {@link #run}, then the store-backed detections over the store the capture just filled,
     * before it closes. Returns the detections' typed {@link AuthoredClaimConflicts.Detection}
     * product (gated on {@code domain}): every caller reads its
     * {@link AuthoredClaimConflicts.Detection#violations() violations} for the error stream, and
     * the LSP/MCP snapshot path additionally reads its
     * {@link AuthoredClaimConflicts.Detection#fieldConflicts() field conflicts} for the
     * {@code Conflicted} projection overlay; the store handle never escapes. The detection runs
     * against whichever store the capture landed in, shared file and in-memory fallback alike,
     * so a cache demotion changes cost and never verdicts.
     */
    public static AuthoredClaimConflicts.Detection runWithDetections(Path storeDirectory, GraphIdentity graph,
                                                          TypeDefinitionRegistry registry, JooqCatalog jooq,
                                                          List<CompletionData.ExternalReference> extensions,
                                                          NodeDeclaration nodes, ClaimDomain domain) {
        Objects.requireNonNull(domain, "domain");
        return runInternal(storeDirectory, graph, registry, jooq, extensions, nodes, domain);
    }

    private static AuthoredClaimConflicts.Detection runInternal(Path storeDirectory, GraphIdentity graph,
                                                     TypeDefinitionRegistry registry, JooqCatalog jooq,
                                                     List<CompletionData.ExternalReference> extensions,
                                                     NodeDeclaration nodes, ClaimDomain domain) {
        if (storeDirectory != null) {
            try (GraphitronModelStore store = GraphitronModelStore.openAt(storeDirectory)) {
                if (store.location().isEmpty()) {
                    // openAt already fell back to an in-memory store; use it as-is.
                    capture(store.dsl(), false, graph, registry, jooq, extensions, nodes);
                    return detect(store.dsl(), graph, domain);
                }
                if (!store.warm() || ownsGraph(store.dsl(), graph)) {
                    if (captureWithRetry(store, graph, registry, jooq, extensions, nodes)) {
                        return detect(store.dsl(), graph, domain);
                    }
                }
            }
        }
        try (GraphitronModelStore store = GraphitronModelStore.open()) {
            capture(store.dsl(), false, graph, registry, jooq, extensions, nodes);
            return detect(store.dsl(), graph, domain);
        }
    }

    /**
     * The detection pass over a freshly captured store; a {@code null} domain is {@link #run}'s
     * no-detection arm, which also writes no reach rows. The walk's reach lands as
     * {@code walk_claim_domain} rows first, so the {@code intent_authored_claim_conflict} view's
     * domain-gate join answers over exactly the domain this detection is gated on.
     */
    private static AuthoredClaimConflicts.Detection detect(DSLContext dsl, GraphIdentity graph, ClaimDomain domain) {
        if (domain == null) {
            return AuthoredClaimConflicts.Detection.empty();
        }
        ClaimDomainRows.write(dsl, graph.name(), domain);
        return AuthoredClaimConflicts.detect(dsl, graph.name(), domain);
    }

    /**
     * Attempts the warm capture, retrying once against the same store before giving up, so a
     * transient concurrency casualty (cleared by the time the retry runs) is told apart from a
     * deterministic capture bug (fails the same way both times). Returns {@code true} once either
     * attempt lands; {@code false} tells the caller to fall back to an in-memory capture instead.
     */
    private static boolean captureWithRetry(GraphitronModelStore store, GraphIdentity graph,
                                            TypeDefinitionRegistry registry, JooqCatalog jooq,
                                            List<CompletionData.ExternalReference> extensions,
                                            NodeDeclaration nodes) {
        try {
            capture(store.dsl(), store.warm(), graph, registry, jooq, extensions, nodes);
            return true;
        } catch (DataAccessException first) {
            LOG.debug("shared fact store write failed; retrying once before recapturing in memory", first);
        }
        try {
            capture(store.dsl(), store.warm(), graph, registry, jooq, extensions, nodes);
            return true;
        } catch (DataAccessException second) {
            LOG.warn("shared fact store write for graph '{}' failed twice in a row; this looks like a "
                    + "deterministic capture bug rather than a concurrency casualty, and warm start will "
                    + "stay unavailable for this graph until it is fixed. Recapturing in memory for this run.",
                graph.name(), second);
            return false;
        }
    }

    /**
     * Fills {@code dsl}'s store from all three inputs. Separate from {@link #run} so a caller that
     * wants to query the result (the agreement and gate tests) can own the store's lifetime.
     *
     * @param jooq  the catalog to walk, or {@code null} for a caller with none in hand. The catalog
     *              itself rather than the {@code CatalogFacts} projection over it: that projection
     *              is shaped for the MCP catalog tools, and a narrowing it makes for them would
     *              land here as a fact about the consumer's database.
     * @param nodes the nodehood predicate macro expansion needs, since federation's key synthesis
     *              fires on nodes and nodehood can be inferred from the catalog rather than
     *              declared. A predicate built on a null catalog reduces it to {@code @node}
     *              presence, which is what a caller with no catalog in hand should get.
     */
    public static void capture(DSLContext dsl, GraphIdentity graph, TypeDefinitionRegistry registry,
                               JooqCatalog jooq, List<CompletionData.ExternalReference> extensions,
                               NodeDeclaration nodes) {
        capture(dsl, false, graph, registry, jooq, extensions, nodes);
    }

    /**
     * Fills {@code dsl}'s store, reconciling it first when it already holds rows. One transaction
     * end to end: the {@code store_graph} upsert leads, so a concurrent writer of the same graph
     * serializes on the anchor row instead of interleaving deletes with inserts, and a run that
     * dies mid-load leaves the previous committed state instead of a half-written partition.
     *
     * @param warm whether the store opened onto a previous run's rows. A cold store needs no
     *             reconciliation; a warm one is cleared of everything this run owns and rewrites,
     *             and keeps the partitions whose source still hashes to what it recorded
     */
    public static void capture(DSLContext dsl, boolean warm, GraphIdentity graph,
                               TypeDefinitionRegistry registry, JooqCatalog jooq,
                               List<CompletionData.ExternalReference> extensions,
                               NodeDeclaration nodes) {
        dsl.transaction(tx -> {
            DSLContext txDsl = tx.dsl();
            var sink = new FactSink(txDsl, graph.name());
            var sources = new ClasspathSources();
            writeGraph(txDsl, sources, graph);
            if (warm) {
                StoreRefresh.prepare(sink, sources, extensions, graph.name());
            }
            writeRecipe(sink, graph.recipe());
            SdlFactCapture.capture(sink, registry, nodes, sources);
            CatalogFactCapture.capture(sink, jooq, extensions, sources);
            sink.flush();
            // The capture-cadence derivation stratum: materialized derivations re-derive from
            // the flushed rows inside the same transaction, so they are current exactly when
            // the partition they derive from is.
            ReachabilityRows.derive(txDsl, graph.name());
            InputOccurrencePaths.derive(txDsl, graph.name());
            sources.commitStamps(txDsl);
        });
    }

    /** SDL-only capture, for callers with no catalog in hand. */
    public static void capture(DSLContext dsl, GraphIdentity graph, TypeDefinitionRegistry registry) {
        capture(dsl, graph, registry, null, List.of(), new NodeDeclaration(null));
    }

    /**
     * Whether this run may write under its graph name: true when the store has no row for it or
     * the recorded base directory is this run's own. The check lives here, where the store is
     * open and the row readable, rather than in the mojo, which never reads the store.
     */
    private static boolean ownsGraph(DSLContext dsl, GraphIdentity graph) {
        String recorded = dsl.select(STORE_GRAPH.BASE_DIR).from(STORE_GRAPH)
            .where(STORE_GRAPH.GRAPH_NAME.eq(graph.name()))
            .fetchOne(0, String.class);
        if (recorded == null || recorded.equals(graph.baseDir().toString())) {
            return true;
        }
        LOG.warn("graph '{}' is already recorded in the shared fact store for {}, but this run's "
                + "base directory is {}. Leaving that partition alone and capturing in memory; "
                + "set <graphName> so the two modules stop claiming one name.",
            graph.name(), recorded, graph.baseDir());
        return false;
    }

    /**
     * Upserts the graph's anchor row: its base directory, its build identity (the build file's
     * path and content hash, both null on a programmatic run), and a fresh {@code last_captured}.
     * First write of the run on purpose: every SDL root's foreign key lands on this row, and a
     * concurrent same-graph writer blocks on it here instead of colliding later.
     */
    private static void writeGraph(DSLContext dsl, ClasspathSources sources, GraphIdentity graph) {
        String buildFilePath = null;
        String buildFileStamp = null;
        if (graph.recipe() != null && graph.recipe().buildFile() != null) {
            Path buildFile = graph.recipe().buildFile();
            buildFilePath = buildFile.toString();
            buildFileStamp = sources.stamp(buildFile);
        }
        String baseDir = graph.baseDir().toString();
        var now = LocalDateTime.now();
        dsl.insertInto(STORE_GRAPH)
            .set(STORE_GRAPH.GRAPH_NAME, graph.name())
            .set(STORE_GRAPH.BASE_DIR, baseDir)
            .set(STORE_GRAPH.BUILD_FILE_PATH, buildFilePath)
            .set(STORE_GRAPH.BUILD_FILE_STAMP, buildFileStamp)
            .set(STORE_GRAPH.LAST_CAPTURED, now)
            .onDuplicateKeyUpdate()
            .set(STORE_GRAPH.BASE_DIR, baseDir)
            .set(STORE_GRAPH.BUILD_FILE_PATH, buildFilePath)
            .set(STORE_GRAPH.BUILD_FILE_STAMP, buildFileStamp)
            .set(STORE_GRAPH.LAST_CAPTURED, now)
            .execute();
    }

    /**
     * Transcribes the graph's SDL recipe, written fresh by every run from its resolved
     * configuration; the warm path's graph-scoped clear has already emptied the previous run's
     * rows. Buffered through the sink like any graph-keyed rows, so they carry the graph stamp.
     */
    private static void writeRecipe(FactSink sink, SchemaRecipe recipe) {
        if (recipe == null) {
            return;
        }
        int ordinal = 0;
        for (SchemaRecipe.Binding binding : recipe.bindings()) {
            var row = sink.dsl().newRecord(STORE_GRAPH_SCHEMA_INPUT);
            row.setOrdinal(ordinal++);
            row.setPattern(binding.pattern());
            row.setTag(binding.tag().orElse(null));
            row.setDescriptionNote(binding.descriptionNote().orElse(null));
            sink.add(row);
        }
        int position = 0;
        for (String extension : recipe.extensions()) {
            var row = sink.dsl().newRecord(STORE_GRAPH_SCHEMA_EXTENSION);
            row.setOrdinal(position++);
            row.setExtension(extension);
            sink.add(row);
        }
    }
}
