package no.sikt.graphitron.model.capture;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.capture.catalog.CatalogFactCapture;
import no.sikt.graphitron.model.capture.config.ConfigurationFactCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.capture.graphitron.GraphitronFactCapture;
import no.sikt.graphitron.model.capture.jooq.JooqFactCapture;
import no.sikt.graphitron.model.capture.sdl.SdlFactCapture;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.derive.ArgMappingCandidates;
import no.sikt.graphitron.model.derive.ArgmappingProjectionDefects;
import no.sikt.graphitron.model.derive.AuthoredClaimConflicts;
import no.sikt.graphitron.model.derive.AuthoredClaimRejectionRows;
import no.sikt.graphitron.model.derive.ClassificationDomainCapture;
import no.sikt.graphitron.model.derive.ClassifiedRun;
import no.sikt.graphitron.model.derive.InputOccurrencePaths;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.derive.NameMatchedKeys;
import no.sikt.graphitron.model.derive.NodeIdDecodeCoverageFacts;
import no.sikt.graphitron.model.derive.NodeIdDecodeDefects;
import no.sikt.graphitron.model.derive.NodeIdPolymorphicDecodeDefects;
import no.sikt.graphitron.model.derive.NodeIdLandingDefects;
import no.sikt.graphitron.model.derive.ReferenceForParticipantDefects;
import no.sikt.graphitron.model.derive.RefreshProgress;
import no.sikt.graphitron.model.derive.ResolvedKeyProjections;
import no.sikt.graphitron.model.derive.StoreDetections;
import no.sikt.graphitron.model.derive.TypeBackingRows;
import no.sikt.graphitron.model.derive.UnlowerableOrderingRejectionRows;
import no.sikt.graphitron.model.derive.UnlowerableOrderings;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.run.CapturePort;
import no.sikt.graphitron.model.run.CaptureRequest;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.RunStore;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.sink.FactSink;
import no.sikt.graphitron.model.sources.ClasspathSources;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

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
 * <p>A run captures exactly one graph; the store may hold many, though only a session's does. A
 * one-shot goal's store is build output under its own module's target, so its partition has no
 * siblings and the graph scoping a warm open applies is scoping nothing; the sharing this used to
 * describe is the session store's alone. A run that cannot have the store it asked for fails and
 * says what to do about it, which {@link RunStore} states.
 */
public final class FactCapture {

    private static final Logger LOG = LoggerFactory.getLogger(FactCapture.class);

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
     * schema loader ran on the way here, so no source was refused, and the walk records a source
     * membership for every input it was handed.
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
     * rooted traversal runs over the schema this assembly produced, so handing over an assembly of
     * a different registry would classify a document the store does not hold.
     */
    public static void run(Path storeDirectory, GraphIdentity graph, SubjectConfig config,
                           TypeDefinitionRegistry registry, SchemaAssembly assembly,
                           SdlVerdicts verdicts, Map<String, SchemaInput> attribution,
                           JooqCatalog jooq, List<CompletionData.ExternalReference> extensions) {
        CapturePort.perRun(storeDirectory).capture(CaptureRequest.unseeded(graph, config, registry,
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
        return CapturePort.perRun(storeDirectory).captureAndRead(CaptureRequest.unseeded(graph,
            config, registry, assembly, verdicts, attribution, jooq, extensions, classified),
            after);
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
     * <p>The {@code @nodeId} families read only SDL facts, the catalog and the classpath census, and
     * share the classified-run arm anyway: a run with no classified model is a run whose verdict has already
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
                    NodeIdPolymorphicDecodeDefects.detect(dsl, graphName),
                    NodeIdLandingDefects.detect(dsl, graphName),
                    ReferenceForParticipantDefects.detect(dsl, graphName),
                    UnlowerableOrderings.detect(dsl, graphName),
                    ResolvedKeyProjections.read(dsl, graphName),
                    NodeIdDecodeCoverageFacts.read(dsl, graphName));
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
     * <p>Every row a capture takes fails rather than waits, the connection carrying no lock budget
     * at all. This used to be the one row that did: a short budget here and a generous one after
     * it, because the rows after the anchor were the store-global families two graphs' captures
     * write concurrently. Two modules are two files now, so there is no second capture to serialize
     * against and nothing left for either budget to buy.
     *
     * <p><b>One exception, on the store where the contract protects nothing.</b> A capture into a
     * store no registered target holds a row in commits its facts and then refreshes the
     * materialized targets outside this transaction, on
     * {@link Materializations#refreshAnalysing}'s cadence, because on such a store every target is
     * empty and a refresh inside the transaction cannot be given the statistics its own statements
     * are planned against. A commit between two registrations empties nothing that was committed
     * there. What the window does publish is this graph's rows arriving between those commits, which
     * is the state {@link Materializations#refreshAll} already publishes on every reader open. Every
     * other capture is one transaction exactly as above. Which store that is,
     * {@link Materializations#analysingCadenceApplies} decides, over the register's own state rather
     * than over the anchor row this pass is about to write.
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
     * already. The gatherer's own stages run in order here: the per-source declarations, the
     * decode of the graphitron directives among them, and last the rooted traversal over what
     * assembled. Handing over an assembly of some other registry would classify a document this
     * store does not hold.
     */
    public static void capture(DSLContext dsl, boolean warm, GraphIdentity graph,
                               SubjectConfig config, TypeDefinitionRegistry registry,
                               SchemaAssembly assembly, SdlVerdicts verdicts,
                               Map<String, SchemaInput> attribution, JooqCatalog jooq,
                               List<CompletionData.ExternalReference> extensions) {
        // Nothing seeded, so nothing has been read: every source this capture records is hashed
        // inside it, and entry is therefore before the read the instant dates. This is the one
        // shape in which an instant may be taken here rather than carried in.
        capture(dsl, warm, graph, config, registry, assembly, verdicts, attribution, jooq,
            extensions, Map.of(), LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * {@link #capture(DSLContext, boolean, GraphIdentity, SubjectConfig, TypeDefinitionRegistry,
     * SchemaAssembly, SdlVerdicts, Map, JooqCatalog, List)} for a caller that already established
     * what each classpath jar hashes to, which is every caller reading its census through a
     * {@link no.sikt.graphitron.model.classpath.ClasspathCensus}. The retention decision and the
     * commit-time stamping both go through the same memo, so supplying it means each jar of a
     * round is identified once rather than once per consumer.
     *
     * <p>The values have to be <em>this</em> round's. What the seeded memo asserts is that the
     * bytes the census parsed are the bytes the partition describes; a value from any other moment
     * would record a stamp for bytes nobody read. {@code readAt} is the same assertion about the
     * same moment, which is why there is no arity taking the stamps without it: an instant this
     * method took for itself would be later than the reads the stamps describe, and every change
     * that landed in between would be recorded as though it had not happened.
     *
     * @param classpathStamps the round's per-jar identities keyed by path
     * @param readAt          when the round began reading them, taken before it opened the first
     */
    public static void capture(DSLContext dsl, boolean warm, GraphIdentity graph,
                               SubjectConfig config, TypeDefinitionRegistry registry,
                               SchemaAssembly assembly, SdlVerdicts verdicts,
                               Map<String, SchemaInput> attribution, JooqCatalog jooq,
                               List<CompletionData.ExternalReference> extensions,
                               Map<String, String> classpathStamps, LocalDateTime readAt) {
        capture(dsl, warm, graph, config, registry, assembly, verdicts, attribution, jooq,
            extensions, classpathStamps, readAt, refreshLines());
    }

    /**
     * {@link #capture(DSLContext, boolean, GraphIdentity, SubjectConfig, TypeDefinitionRegistry,
     * SchemaAssembly, SdlVerdicts, Map, JooqCatalog, List, Map, LocalDateTime)} reporting the
     * materialization refresh to {@code refresh} rather than to this class's log lines.
     *
     * <p>The events are the same either way, {@link #refreshLines} being one rendering of them, so
     * this arity changes nothing about what a capture does. It exists because the cadence chosen
     * above is only observable from inside the pass: both cadences leave the same store behind, and
     * the invariant that broke, that the cadence is decided on the register's state and not on the
     * anchor row, is therefore not assertable from the outside at all. A caller with somewhere
     * better to put a registration's events than a log supplies one, and
     * {@code RefreshPrerequisiteStatisticsTest} is what that buys.
     *
     * @param refresh what the refresh pass reports to, never null; {@link RefreshProgress#none()}
     *                for a caller that wants silence
     */
    public static void capture(DSLContext dsl, boolean warm, GraphIdentity graph,
                               SubjectConfig config, TypeDefinitionRegistry registry,
                               SchemaAssembly assembly, SdlVerdicts verdicts,
                               Map<String, SchemaInput> attribution, JooqCatalog jooq,
                               List<CompletionData.ExternalReference> extensions,
                               Map<String, String> classpathStamps, LocalDateTime readAt,
                               RefreshProgress refresh) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(refresh, "refresh");
        Objects.requireNonNull(attribution, "attribution");
        Objects.requireNonNull(verdicts, "verdicts");
        Objects.requireNonNull(assembly, "assembly");
        // Asked before the transaction opens, of the register rather than of store_graph. The
        // condition used to be "this store holds no graph", which is a proxy three unrelated
        // writers of the anchor row defeat, the build path's configuration capture among them;
        // Materializations.analysingCadenceApplies carries what the cadence actually turns on.
        boolean analysingCadence = Materializations.analysingCadenceApplies(dsl);
        var sources = new ClasspathSources(classpathStamps, readAt);
        dsl.transaction(tx -> {
            DSLContext txDsl = tx.dsl();
            var sink = new FactSink(txDsl, graph.name(), readAt);
            writeGraph(txDsl, sources, graph, config);
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
            // documents move on a keystroke. The resolution stages depend on both and therefore run
            // last; the decode is not among them, the walk writing the as-written half as it goes.
            ConfigurationFactCapture.capture(sink, config);
            sink.flush();
            // The catalog family, written once. These were two gatherers over the same fourteen
            // relations, one per capture entry point. Comparing the whole store after each found
            // three differences, all now closed in the one that stays: two columns of
            // sql_enum_binding, the source registry and its graph membership, and the record
            // supertype closure. What is left here is the classpath references, which it cannot
            // produce, and the order is a foreign key.
            JooqFactCapture.capture(txDsl, graph.name(), jooq, readAt);
            CatalogFactCapture.capture(sink, extensions, sources);
            sink.flush();
            // The catalog's own closure, over the rows the two crawlers above have just flushed.
            // A stage of the catalog gatherer rather than a derivation: it reads no graph and no
            // directive, so it is a function of the store's catalog and not of this run.
            NameMatchedKeys.derive(txDsl);
            // The document gatherer's entries, which this pass has to write because the relations
            // moving off the walk are derived from them and from nothing else. It straddles the
            // walk rather than preceding it: nothing here meets a row the walk writes, the entry
            // stratum being keyed by written position, but graphitron's anchors below key into the
            // graphql_ ones and those are still the walk's to write. The order is therefore the
            // one production already has, with the walk's rows the ones a reader sees wherever
            // both still produce; what changes is that the overlap is no longer the whole.
            var documents = readCorpus(txDsl, graph, config, readAt);
            GraphQLAstCapture.captureEntries(txDsl, graph, documents, readAt);
            GraphitronAstCapture.captureEntries(txDsl, graph, documents, readAt);
            SdlFactCapture.capture(sink, registry, sources, attribution,
                verdicts.refusedSourceNames());
            sink.flush();
            // Between the walk and the stages, which is the only place either can go. Both key into
            // the graphql_ anchors, so neither can precede the walk that writes them; the stages
            // below read what they derive, so neither can follow them. The index is written here
            // and not by the anchor writer this pass skips, because a reading has to write what its
            // own stages read: a graphitron anchor referencing a written position finds no index
            // row to reference otherwise, this pass being the one its stages run in.
            GraphQLAstCapture.captureAstIndex(txDsl, graph.name(), readAt);
            GraphitronAstCapture.anchor(txDsl, graph, readAt);
            GraphitronFactCapture.capture(sink, txDsl, graph.name(), readAt);
            sink.flush();
            // The capture-cadence derivation stratum: materialized derivations re-derive from
            // the flushed rows inside the same transaction, so they are current exactly when
            // the partition they derive from is. Statement order is load-bearing one way only:
            // the hand-written producers run before the registered refresh, and the derived
            // dependency order cannot see a hand-written derivation's reads, those being jOOQ
            // code rather than stored view definitions.
            ClassificationDomainCapture.derive(txDsl, graph.name(),
                assembly instanceof SchemaAssembly.Assembled a ? a.schema() : null);
            InputOccurrencePaths.derive(txDsl, graph.name());
            ArgMappingCandidates.derive(txDsl, graph.name());
            TypeBackingRows.derive(txDsl, graph.name());
            AuthoredClaimRejectionRows.derive(txDsl, graph.name());
            if (!analysingCadence) {
                Materializations.refresh(txDsl, graph.name(), refresh);
                sources.commitStamps(txDsl);
            }
        });
        if (analysingCadence) {
            // The one exception to the paragraph above, and the whole of it: a store no registered
            // target held a row in when this capture began refreshes outside this transaction, one
            // committed transaction per registration, analysing each target as it refills it. Every
            // target on such a store is empty, so the pass inside the transaction plans every
            // statement it issues with no selectivity on anything it reads, which on a consumer-size
            // schema is hours rather than a factor; Materializations.refreshAnalysing carries the
            // measurement, and carries why a commit between two registrations empties nothing that
            // was committed there. Nothing before this point is conditional: the facts, the anchor
            // row and the hand-written derivations are written the same way on both paths.
            Materializations.refreshAnalysing(dsl, graph.name(), refresh);
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
        // readers a captured store has, the build path's diagnostics among them. On the analysing
        // path it is the idempotent restatement of what that pass already analysed, kept so that one
        // call states the whole register's statistics on every path out of a capture.
        Materializations.analyse(dsl);
        // The one capture-cadence writer that cannot run beside its siblings above, and the reason
        // is a dependency rather than a preference: the view it renders reads
        // intent_field_scope_table, which the refresh above is what fills, so a call inside the
        // load transaction would render the previous capture's rows. Its own transaction after the
        // refresh, and after the analyse so the read is planned against current statistics, is
        // therefore the earliest point at which it can see what this capture landed. Nothing the
        // build path reads waits on it: the rejection it stores is for the diagnostics surface, and
        // the error stream mints the same value off the view directly, so a reader arriving in the
        // window between the refresh and this write sees the diagnostic missing rather than wrong.
        dsl.transaction(tx -> UnlowerableOrderingRejectionRows.derive(tx.dsl(), graph.name()));
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
     * This pass's own reading of the SDL corpus, in the shape the document gatherers take.
     *
     * <p>Transitional, and deliberately narrower than {@link GraphQLSourceCapture}. That gatherer
     * owns the store's record of what was read, deriving it from configuration and writing this
     * graph's source membership; this pass derives its read set from what it parsed and writes the
     * membership itself through the walk's sink. Running the owner here would write a read set the
     * walk disagrees with and meet the walk's own membership rows on their key, so this pass keeps
     * parsing for itself until the walk goes, and it goes with the walk.
     *
     * <p>A source that would not parse gets its registry row and no entry in the list, which is
     * this pass's shape rather than the owner's: the gatherers below are handed the list only to
     * transcribe from it, the sweeps that want a broken file named being the owner's callers'.
     *
     * <p>Every document is reported as changed, which is the reading that cannot be wrong: nothing
     * here computes a content stamp, and unknown has to mean recapture rather than skip.
     */
    private static List<GraphQLSourceCapture.SourceDocument> readCorpus(
            DSLContext dsl, GraphIdentity graph, SubjectConfig config, LocalDateTime readAt) {
        var parse = SchemaLoader.parsePerSource(config.schemaFiles(graph.baseDir()));
        var documents = new ArrayList<GraphQLSourceCapture.SourceDocument>();
        for (var document : parse.perSource()) {
            writeSource(dsl, document.sourceName(), readAt);
            documents.add(new GraphQLSourceCapture.SourceDocument(
                document.sourceName(), document.registry(), true));
        }
        for (var failure : parse.failures()) {
            writeSource(dsl, failure.sourceName(), readAt);
        }
        return List.copyOf(documents);
    }

    /**
     * The registry row every one of a document's rows hangs its {@code source_ref} on. Written from
     * the parse's own source name, so the bundled directive vocabulary gets a row on the same terms
     * as an author's file: it is a document this reading read.
     */
    private static void writeSource(DSLContext dsl, String sourceName, LocalDateTime readAt) {
        var t = STORE_SOURCE;
        var mtime = modifiedAt(sourceName);
        dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.MTIME, t.LAST_SEEN, t.READ_AT)
            .values(sourceName, "SCHEMA_FILE", mtime, readAt, readAt)
            .onDuplicateKeyUpdate()
            .set(t.MTIME, mtime)
            .set(t.LAST_SEEN, readAt)
            .set(t.READ_AT, readAt)
            .execute();
    }

    /** When the file was last written, or null for a source that is not a file on disk. */
    private static LocalDateTime modifiedAt(String sourceName) {
        try {
            return LocalDateTime.ofInstant(
                Files.getLastModifiedTime(Path.of(sourceName)).toInstant(),
                ZoneId.systemDefault()).withNano(0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Upserts the graph's anchor row: its base directory, its build identity (the build file's
     * path and content hash, both null on a programmatic run), and a fresh {@code last_captured}.
     * First write of the run on purpose: every SDL root's foreign key lands on this row, so a
     * concurrent same-graph writer meets it here rather than colliding later, and meeting it is
     * the end of that run rather than a wait.
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
