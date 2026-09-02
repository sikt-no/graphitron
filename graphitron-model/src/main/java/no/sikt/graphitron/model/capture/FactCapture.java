package no.sikt.graphitron.model.capture;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.derive.RefreshProgress;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.derive.ArgmappingProjectionDefects;
import no.sikt.graphitron.model.derive.AuthoredClaimConflicts;
import no.sikt.graphitron.model.derive.AuthoredClaimRejectionRows;
import no.sikt.graphitron.model.derive.NodeIdDecodeDefects;
import no.sikt.graphitron.model.derive.ReferenceForParticipantDefects;
import no.sikt.graphitron.model.derive.ResolvedKeyProjections;
import no.sikt.graphitron.model.derive.StoreDetections;
import no.sikt.graphitron.model.derive.ClassifiedRun;
import no.sikt.graphitron.model.derive.ArgMappingCandidates;
import no.sikt.graphitron.model.derive.InputOccurrencePaths;
import no.sikt.graphitron.model.derive.TypeBackingRows;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import no.sikt.graphitron.model.run.CapturePort;
import no.sikt.graphitron.model.run.CaptureRequest;
import no.sikt.graphitron.model.capture.catalog.CatalogFactCapture;
import no.sikt.graphitron.model.derive.ClassificationDomainCapture;
import no.sikt.graphitron.model.sources.ClasspathSources;
import no.sikt.graphitron.model.capture.config.ConfigurationFactCapture;
import no.sikt.graphitron.model.sink.FactSink;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.capture.graphitron.GraphitronFactCapture;
import no.sikt.graphitron.model.run.RunStore;
import no.sikt.graphitron.model.capture.sdl.SdlFactCapture;
import no.sikt.graphitron.model.capture.verdict.SdlVerdictCapture;
import no.sikt.graphitron.model.run.SubjectConfig;

/**
 * Entry point for the generator's capture loads: fills a fact store from the parsed SDL, the jOOQ
 * catalog, and the consumer's compiled extension classes.
 *
 * <p>Which store that is, and what a run does when it cannot have the shared one, is
 * {@link RunStore}'s question rather than this class's: capture writes the rows it found and the
 * store it writes into is handed to it.
 *
 * <p>Both loads are infallible by construction, and construction is the only guarantee in play.
 * The {@link TypeDefinitionRegistry} validates nothing, so every capture path is tolerant: what
 * does not fit records raw and located rather than throwing. Capture is total, with no
 * reachability pruning; a primary-key violation on any base relation is therefore a capture bug,
 * never something an author's schema can provoke.
 *
 * <p>The store's window is the caller's, not this class's. {@link #runAndRead} keeps the store
 * open across whatever the caller does next and hands it a {@link StoreHandle}, which is what lets
 * the build pipeline validate and then plan against the same open store the capture just filled.
 * Capture decides only that its own writes and the detections happen first. Which store a caller
 * gets and for how long is {@link CapturePort}'s, this class's entry points being the single-pass
 * shorthand over its per-call arm.
 *
 * <p>The store has readers: every capture runs the store-backed rule families over the freshly
 * captured rows and yields the {@link StoreDetections} product they share (the violations for the
 * caller's error stream, and the field-conflict claims the snapshot's {@code Conflicted}
 * projection overlay consumes), so what those detections report is decided by the store's
 * content. Two families run today: the authored-claim conflict rule
 * ({@link AuthoredClaimConflicts}, narrowed to the classification domain, which is the population
 * a build can fail on) and the {@code argMapping} node-id projection rules
 * ({@link ArgmappingProjectionDefects}). Every other relation is still populated beside the live
 * pipeline and read by nothing; consumers migrate onto it one at a time.
 *
 * <p>A run captures exactly one graph; the store may hold many. The persisted store is shared by
 * every module of a workspace, so a warm open reconciles only what this run owns; every way that
 * sharing can fail ends in a private store holding the same rows, which {@link RunStore} states.
 */
public final class FactCapture {

    private static final Logger LOG = LoggerFactory.getLogger(FactCapture.class);

    /**
     * How long a capture waits for the {@code store_graph} anchor row, in milliseconds, before
     * giving up the shared store and capturing in memory. Two seconds: long enough to absorb a
     * commit already in flight, since a capture that has reached its own commit releases the row in
     * milliseconds and there is no reason to lose warmth to a race that close; short enough that a
     * human reads the pause as the build starting rather than as the build stopping.
     *
     * <p>Deliberately far below {@link GraphitronModelStore#FILE_LOCK_MILLIS}, which every other row
     * a capture takes keeps, because this is the one row where waiting buys nothing. Blocking here
     * means another writer is mid-capture of the same graph under the same base directory (the
     * ownership check in {@link RunStore} having already refused the other case), so waiting buys
     * the right to delete that capture and write it again identically. Waiting on the rows a
     * capture takes <em>after</em> the anchor is a different bargain: those are the store-global families two
     * different graphs' captures write concurrently, where the other writer is committing rows this
     * one also needs and a writer that waits its turn beats one that falls back cold.
     */
    public static final long ANCHOR_LOCK_MILLIS = 2_000;

    private FactCapture() {}

    /**
     * Runs both loads against the store for {@code storeDirectory} and closes it.
     *
     * <p>Which store that is, what happens when it cannot be the shared one, and what a build is
     * told about it are all {@link RunStore#forRun}'s, stated there. What holds whichever way it
     * goes is that the two stores differ in cost and never in content: a warm store is refreshed
     * to exactly the rows a cold load would have produced, and the agreement anchors are stated
     * against both.
     */
    public static void run(Path storeDirectory, GraphIdentity graph, SubjectConfig config,
                           TypeDefinitionRegistry registry, Map<String, SchemaInput> attribution,
                           JooqCatalog jooq, List<CompletionData.ExternalReference> extensions) {
        run(storeDirectory, graph, config, registry, SdlVerdicts.none(), attribution, jooq,
            extensions);
    }

    /**
     * {@link #run} for a caller that handed over a registry it built itself: no stage of the
     * schema loader ran on the way here, so no stage refused anything, and the verdict relations
     * record the same emptiness a document read clean would leave.
     */
    public static void run(Path storeDirectory, GraphIdentity graph, SubjectConfig config,
                           TypeDefinitionRegistry registry, SdlVerdicts verdicts,
                           Map<String, SchemaInput> attribution,
                           JooqCatalog jooq, List<CompletionData.ExternalReference> extensions) {
        run(storeDirectory, graph, config, registry, SchemaAssembly.of(registry), verdicts,
            attribution, jooq, extensions);
    }

    /**
     * {@link #run(Path, GraphIdentity, SubjectConfig, TypeDefinitionRegistry, SdlVerdicts, Map,
     * JooqCatalog, List)} for a caller that has already assembled {@code registry} and would
     * otherwise pay for a second assembly. The two arguments have to describe one read: the
     * gatherer's assembly stage is where the {@code ASSEMBLY} verdicts come from, so handing over an
     * assembly of a different registry would record a verdict on a document the store does not hold.
     */
    public static void run(Path storeDirectory, GraphIdentity graph, SubjectConfig config,
                           TypeDefinitionRegistry registry, SchemaAssembly assembly,
                           SdlVerdicts verdicts, Map<String, SchemaInput> attribution,
                           JooqCatalog jooq, List<CompletionData.ExternalReference> extensions) {
        CapturePort.perRun(storeDirectory).capture(new CaptureRequest(graph, config, registry,
            assembly, verdicts, attribution, jooq, extensions, ClassifiedRun.absent()));
    }

    /**
     * {@link #run}, then the store-backed detections over the store the capture just filled, with
     * the caller's own reads running inside the same window before it closes. Every caller reads
     * the detections' {@link StoreDetections#violations() violations} for its error stream, and the
     * LSP/MCP snapshot path additionally reads the
     * {@link StoreDetections#fieldConflicts() field conflicts} for the {@code Conflicted}
     * projection overlay. The detections run against whichever store the capture landed in, shared
     * file and in-memory fallback alike, so a cache demotion changes cost and never verdicts.
     *
     * <p>The order stays the caller's. Capture and detection are done before {@code after} runs,
     * which is the only sequencing this method imposes; that the build pipeline validates before
     * it plans is the pipeline's rule and is stated there, because producing a plan for a schema
     * validation would have rejected is a mistake capture cannot see.
     *
     * <p>A store per call, which is the {@link CapturePort#perRun} arm. A caller that runs more
     * than one pass wants {@link CapturePort#holding} instead and builds the request itself; this
     * method is the shorthand for a caller with a single pass and a positional argument list
     * already in hand.
     */
    public static <T> T runAndRead(Path storeDirectory, GraphIdentity graph,
                                   SubjectConfig config,
                                   TypeDefinitionRegistry registry,
                                   SchemaAssembly assembly,
                                   SdlVerdicts verdicts,
                                   Map<String, SchemaInput> attribution,
                                   JooqCatalog jooq,
                                   List<CompletionData.ExternalReference> extensions,
                                   ClassifiedRun classified,
                                   CapturePort.AfterCapture<T> after) {
        return CapturePort.perRun(storeDirectory).captureAndRead(new CaptureRequest(graph, config,
            registry, assembly, verdicts, attribution, jooq, extensions, classified), after);
    }

    /**
     * The detections, then the caller's own reads, both against the store this arm landed in. One
     * method so the three arms above each state the window once rather than pairing a detection
     * call with a continuation call and leaving an arm free to run one without the other.
     */
    public static <T> T read(StoreHandle store, ClassifiedRun classified,
                      CapturePort.AfterCapture<T> after) {
        return after.read(store, detect(store.dsl(), store.graphName(), classified));
    }

    /**
     * The detection pass over a freshly captured store, dispatched on whether the run has a
     * classified model at all: {@link ClassifiedRun.Absent} is {@link #run}'s no-detection arm.
     * The pass writes nothing; every arm reads captured facts and yields detections.
     *
     * <p>Nothing the detections read is gated on the classification walk. The authored-claim conflict
     * rule reads a relation that is total over the authored claims and applies the population its
     * own question needs (the classification domain, derived from captured SDL facts at capture
     * cadence), so its accept line is a fact of the store rather than of the walk's reach.
     *
     * <p>Beside the detections the pass reads the {@code argMapping} family's positive half,
     * {@link ResolvedKeyProjections}, which the plan emits from. It is read here because it was
     * written when this pass was the store's only window; with the window now the caller's, a
     * producer that wants the fact can ask for it directly, and this read is a value the plan is
     * handed rather than a question it puts.
     *
     * <p>The two {@code @nodeId} families read only SDL facts and the classpath census, and share the
     * classified-run arm anyway: a run with no classified model is a run whose verdict has already
     * been pronounced elsewhere, and there is no build for these rejections to fail.
     */
    private static StoreDetections detect(DSLContext dsl, String graphName,
                                          ClassifiedRun classified) {
        return switch (classified) {
            case ClassifiedRun.Absent ignored -> StoreDetections.empty();
            case ClassifiedRun.Present present -> {
                yield new StoreDetections(AuthoredClaimConflicts.detect(dsl, graphName),
                    ArgmappingProjectionDefects.detect(dsl, graphName),
                    NodeIdDecodeDefects.detect(dsl, graphName),
                    ReferenceForParticipantDefects.detect(dsl, graphName),
                    ResolvedKeyProjections.read(dsl, graphName));
            }
        };
    }

    /**
     * Fills {@code dsl}'s store from all three inputs. Separate from {@link #run} so a caller that
     * wants to query the result (the agreement and gate tests) can own the store's lifetime.
     *
     * <p>{@code jooq} is the only catalog-shaped input, and nothing may add a second. The catalog
     * reaches exactly one crawler, so no crawler's rows about one corpus can depend on another's
     * contents; a second parameter carrying a catalog-derived value would reopen that channel while
     * leaving the store's picture indistinguishable from a transcription. A rule over two corpora is
     * a derivation over their captured facts instead, and
     * {@code CaptureCorpusIsolationTest} is what holds the boundary.
     *
     * @param jooq  the catalog to walk, or {@code null} for a caller with none in hand. The catalog
     *              itself rather than any consumer-shaped view over it, since a narrowing made for
     *              one reader would land here as a fact about the consumer's database.
     */
    public static void capture(DSLContext dsl, GraphIdentity graph, SubjectConfig config,
                               TypeDefinitionRegistry registry, Map<String, SchemaInput> attribution,
                               JooqCatalog jooq, List<CompletionData.ExternalReference> extensions) {
        capture(dsl, false, graph, config, registry, SdlVerdicts.none(), attribution, jooq, extensions);
    }

    /**
     * Fills {@code dsl}'s store, reconciling it first when it already holds rows. One transaction
     * end to end: the {@code store_graph} upsert leads, so a concurrent writer of the same graph
     * serializes on the anchor row instead of interleaving deletes with inserts, and a run that
     * dies mid-load leaves the previous committed state instead of a half-written partition.
     *
     * <p>Leading with the anchor also makes it the one row worth failing fast on, which is what
     * {@link #ANCHOR_LOCK_MILLIS} is: every row after it is worth waiting for, and this one is not.
     *
     * <p><b>One exception, on the one store where the contract protects nothing.</b> A capture into a
     * store that holds no graph at all commits its facts and then refreshes the materialized targets
     * outside this transaction, on {@link Materializations#refreshAnalysing}'s cadence, because on
     * such a store every target is empty and a refresh inside the transaction cannot be given the
     * statistics its own statements are planned against. Nothing committed can be emptied there, and
     * no reader can be reading a partition the store does not yet have. What the window does publish
     * is the graph present with its derivations incomplete, which is the state
     * {@link Materializations#refreshAll} already publishes on every reader open. Every other capture
     * is one transaction exactly as above.
     *
     * @param warm whether the store opened onto a previous run's rows. A cold store needs no
     *             reconciliation; a warm one is cleared of everything this run owns and rewrites,
     *             and keeps the partitions whose source still hashes to what it recorded
     */
    public static void capture(DSLContext dsl, boolean warm, GraphIdentity graph,
                               SubjectConfig config, TypeDefinitionRegistry registry,
                               Map<String, SchemaInput> attribution, JooqCatalog jooq,
                               List<CompletionData.ExternalReference> extensions) {
        capture(dsl, warm, graph, config, registry, SdlVerdicts.none(), attribution, jooq,
            extensions);
    }

    /** {@link #capture(DSLContext, boolean, GraphIdentity, SubjectConfig, TypeDefinitionRegistry,
     * Map, JooqCatalog, List)} with the stage verdicts a full read produced. */
    public static void capture(DSLContext dsl, boolean warm, GraphIdentity graph,
                               SubjectConfig config, TypeDefinitionRegistry registry,
                               SdlVerdicts verdicts,
                               Map<String, SchemaInput> attribution, JooqCatalog jooq,
                               List<CompletionData.ExternalReference> extensions) {
        capture(dsl, warm, graph, config, registry, SchemaAssembly.of(registry), verdicts,
            attribution, jooq, extensions);
    }

    /**
     * {@link #capture(DSLContext, boolean, GraphIdentity, SubjectConfig, TypeDefinitionRegistry,
     * SdlVerdicts, Map, JooqCatalog, List)} for a caller holding the assembly of {@code registry}
     * already. The gatherer's own stages run in order here: the per-source declarations, the two
     * document-wide verdicts, the assembly verdict, and last the rooted traversal over what
     * assembled. Handing over an assembly of some other registry would record a verdict on a
     * document this store does not hold.
     */
    public static void capture(DSLContext dsl, boolean warm, GraphIdentity graph,
                               SubjectConfig config, TypeDefinitionRegistry registry,
                               SchemaAssembly assembly, SdlVerdicts verdicts,
                               Map<String, SchemaInput> attribution, JooqCatalog jooq,
                               List<CompletionData.ExternalReference> extensions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(attribution, "attribution");
        Objects.requireNonNull(verdicts, "verdicts");
        Objects.requireNonNull(assembly, "assembly");
        boolean firstGraph = !dsl.fetchExists(STORE_GRAPH);
        var sources = new ClasspathSources();
        dsl.transaction(tx -> {
            DSLContext txDsl = tx.dsl();
            var sink = new FactSink(txDsl, graph.name());
            // The budget is narrowed for the anchor row alone and restored the moment it is held;
            // ANCHOR_LOCK_MILLIS carries why the two rows deserve different answers. Set per
            // capture rather than once at open because SET LOCK_TIMEOUT is a session command and
            // survives the transaction that failed, so the restore has to be reachable again.
            txDsl.execute("SET LOCK_TIMEOUT " + ANCHOR_LOCK_MILLIS);
            writeGraph(txDsl, sources, graph, config);
            txDsl.execute("SET LOCK_TIMEOUT " + GraphitronModelStore.FILE_LOCK_MILLIS);
            if (warm) {
                StoreRefresh.prepare(sink, sources, extensions, graph.name());
            }
            // The gatherers run in the order their declared read edges require, and each one's
            // rows reach the store before the next one starts. A flush is not a commit: the rows
            // land inside this transaction, so a gatherer reads what ran before it through the
            // store rather than through a parameter its caller threaded, and nothing outside the
            // transaction sees a partition mid-load. The two crawlers depend on nothing and on
            // each other least of all, so their order is free and the catalog takes the lead on
            // rate of change: a consumer's database moves on a release cadence where their schema
            // documents move on a keystroke. The decode depends on both and therefore runs last.
            ConfigurationFactCapture.capture(sink, config);
            sink.flush();
            CatalogFactCapture.capture(sink, jooq, extensions, sources);
            sink.flush();
            SdlFactCapture.capture(sink, registry, sources, attribution,
                verdicts.refusedSourceNames());
            sink.flush();
            SdlVerdictCapture.capture(sink, verdicts, assembly);
            sink.flush();
            var synthesizedEdges = GraphitronFactCapture.capture(sink, txDsl, graph.name());
            sink.flush();
            // The capture-cadence derivation stratum: materialized derivations re-derive from
            // the flushed rows inside the same transaction, so they are current exactly when
            // the partition they derive from is. Statement order is load-bearing one way only:
            // the hand-written producers run before the registered refresh, and the derived
            // dependency order cannot see a hand-written derivation's reads, those being jOOQ
            // code rather than stored view definitions.
            ClassificationDomainCapture.derive(txDsl, graph.name(),
                assembly instanceof SchemaAssembly.Assembled a ? a.schema() : null,
                synthesizedEdges);
            InputOccurrencePaths.derive(txDsl, graph.name());
            ArgMappingCandidates.derive(txDsl, graph.name());
            TypeBackingRows.derive(txDsl, graph.name());
            AuthoredClaimRejectionRows.derive(txDsl, graph.name());
            if (!firstGraph) {
                Materializations.refresh(txDsl, graph.name(), refreshLines());
                sources.commitStamps(txDsl);
            }
        });
        if (firstGraph) {
            // The one exception to the paragraph above, and the whole of it: a store that held no
            // graph when this capture began refreshes outside this transaction, one committed
            // transaction per registration, analysing each target as it refills it. Every target on
            // such a store is empty, so the pass inside the transaction plans every statement it
            // issues with no selectivity on anything it reads, which on a consumer-size schema is
            // hours rather than a factor; Materializations.refreshAnalysing carries the measurement,
            // and carries why this is the one store where committing between two registrations
            // publishes nothing. Nothing before this point is conditional: the facts, the anchor row
            // and the hand-written derivations are written the same way on both paths.
            Materializations.refreshAnalysing(dsl, graph.name(), refreshLines());
            // And the stamps follow the refresh rather than the flush, because here they vouch for
            // the derived targets as well: a pass that stops part-way has to leave a null stamp, so
            // that the next run reloads and re-derives the partition instead of retaining one whose
            // targets were never filled. ClasspathSources states the rule this follows.
            dsl.transaction(tx -> sources.commitStamps(tx.dsl()));
        }
        // Statistics on what the refresh above just rewrote, so the planner uses the indexes
        // declared beside the materialized targets. Outside the transaction and not inside it,
        // which Materializations.analyse states the reason for: H2's ANALYZE commits, and a commit
        // between this capture's delete and its inserts would publish the emptied partition the
        // one-transaction contract above exists to prevent. After the commit the store is settled,
        // so analysing here is exactly as safe as the dev session's own call and reaches the
        // readers a captured store has, the build path's diagnostics among them. On the first-graph
        // path it is the idempotent restatement of what that pass already analysed, kept so that one
        // call states the whole register's statistics on every path out of a capture.
        Materializations.analyse(dsl);
    }

    /**
     * What a refresh says on the way past: two lines per capture at info naming the pass, and the
     * per-registration tier at debug, which {@code mvn -X} turns on. Named once because both refresh
     * cadences a capture can take report the same way, and a cadence reporting differently would
     * make the two paths' logs incomparable on exactly the runs anybody reads them for.
     */
    private static RefreshProgress refreshLines() {
        return RefreshProgress.lines(LOG::info, LOG::debug);
    }

    /** SDL-only capture, for callers with no catalog in hand. */
    public static void capture(DSLContext dsl, GraphIdentity graph, SubjectConfig config,
                               TypeDefinitionRegistry registry, Map<String, SchemaInput> attribution) {
        capture(dsl, graph, config, registry, attribution, null, List.of());
    }

    /**
     * Upserts the graph's anchor row: its base directory, its build identity (the build file's
     * path and content hash, both null on a programmatic run), and a fresh {@code last_captured}.
     * First write of the run on purpose: every SDL root's foreign key lands on this row, and a
     * concurrent same-graph writer blocks on it here instead of colliding later, for the short
     * budget {@link #ANCHOR_LOCK_MILLIS} allows it and no longer.
     */
    private static void writeGraph(DSLContext dsl, ClasspathSources sources, GraphIdentity graph,
                                   SubjectConfig config) {
        String buildFilePath = null;
        String buildFileStamp = null;
        Path buildFile = config.recipe().map(SchemaRecipe::buildFile).orElse(null);
        if (buildFile != null) {
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

}
