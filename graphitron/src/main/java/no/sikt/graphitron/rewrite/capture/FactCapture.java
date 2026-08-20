package no.sikt.graphitron.rewrite.capture;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.derive.ArgmappingProjectionDefects;
import no.sikt.graphitron.rewrite.derive.AuthoredClaimConflicts;
import no.sikt.graphitron.rewrite.derive.ResolvedKeyProjections;
import no.sikt.graphitron.rewrite.derive.StoreDetections;
import no.sikt.graphitron.rewrite.derive.ClassifiedRun;
import no.sikt.graphitron.rewrite.derive.InputOccurrencePaths;
import no.sikt.graphitron.rewrite.derive.TypeBackingRows;
import no.sikt.graphitron.rewrite.derive.TypeBackingClassRows;
import no.sikt.graphitron.rewrite.compile.CompileFacts;
import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.schema.SchemaAssembly;
import no.sikt.graphitron.rewrite.schema.SdlVerdicts;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaRecipe;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;

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
 * <p>The store has readers: {@link #runWithDetections} runs every store-backed rule family over
 * the freshly captured rows and returns the {@link StoreDetections} product they share (the
 * violations for the caller's error stream, and the field-conflict claims the snapshot's
 * {@code Conflicted} projection overlay consumes), so what those detections report is decided by
 * the store's content. Two families run today: the authored-claim conflict rule
 * ({@link AuthoredClaimConflicts}, narrowed to the classification domain, which is the population
 * a build can fail on) and the {@code argMapping} node-id projection rules
 * ({@link ArgmappingProjectionDefects}). Every other relation is still populated beside the live
 * pipeline and read by nothing; consumers migrate onto it one at a time.
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
     * The graph a capture run writes under, as a <em>coordinate</em> and nothing else: the partition
     * every SDL row of the run carries, and the base directory the graph's ownership is checked
     * against. Deliberately not also capture's subject, which is what {@link SubjectConfig} is:
     * conflating the two is what put a nullable recipe on this record, and billed callers that hold
     * no configuration at all ({@link CompileFacts} writes {@code javac_diagnostic} rows) for a
     * component they could only ever synthesise.
     */
    public record GraphIdentity(String name, Path baseDir) {
        public GraphIdentity {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(baseDir, "baseDir");
            if (name.isBlank()) {
                throw new IllegalArgumentException("graph name must be non-blank");
            }
            baseDir = baseDir.toAbsolutePath().normalize();
        }
    }

    /**
     * The configuration capture transcribes <em>about</em> its subject graph, one typed value rather
     * than a loose parameter per family member. Absence is explicit per component and structural:
     * a component's emptiness means the run was not asked, and capture writes no row rather than a
     * row carrying a synthesised value.
     *
     * <p>One value because the alternative accumulates: the recipe, the supergraph declaration and
     * every family parameter after them would each arrive as a nullable positional argument on all
     * five public entry points, which is the untyped default door {@link
     * no.sikt.graphitron.rewrite.schema.input.SchemaSource} refuses, rebuilt at the seam narrowing
     * {@link GraphIdentity} cleaned. The attribution map stays outside it, being derived from the
     * run's inputs rather than declared by its author.
     *
     * @param recipe     how the run's schema files were found, transcribed so a currency check can
     *                   re-expand it without building the module
     * @param supergraph which supergraph this graph declared itself a subgraph of, from the
     *                   {@code <supergraph>} parameter. Empty is standalone, which is the default
     *                   rather than a state an author spells
     * @param output     where a generating run wrote, from {@code <outputPackage>},
     *                   {@code <jooqPackage>} and {@code <outputDirectory>}. Empty for a run with no
     *                   output coordinates at all, which is a validate-only run: the package sentinel
     *                   such a run carries is its own admission of that, and transcribing the
     *                   sentinel would mint the derived fact that can disagree
     * @param tenantColumn the database-per-tenant column declaration, from {@code <tenantColumn>};
     *                   empty on a single-tenant build
     * @param lint       the {@code <lint>} suppression, decomposed rather than rendered.
     *                   {@link LintConfig#empty()} carries the no-suppression case, which writes no
     *                   rows, so absence needs no second spelling
     * @param sessionState the {@code <sessionState>} form. Sealed, and
     *                   {@link SessionStateConfig#none()} is the no-configuration arm, so this
     *                   component is never absent and the arm carries what absence would have
     */
    public record SubjectConfig(Optional<SchemaRecipe> recipe, Optional<String> supergraph,
                                Optional<OutputCoordinates> output, Optional<String> tenantColumn,
                                LintConfig lint, SessionStateConfig sessionState) {
        public SubjectConfig {
            Objects.requireNonNull(recipe, "recipe");
            Objects.requireNonNull(supergraph, "supergraph");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(tenantColumn, "tenantColumn");
            Objects.requireNonNull(lint, "lint");
            Objects.requireNonNull(sessionState, "sessionState");
        }

        /** A subject that declared nothing at all. */
        public static SubjectConfig none() {
            return new SubjectConfig(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), LintConfig.empty(), SessionStateConfig.none());
        }

        /** A subject whose only declaration is its recipe. */
        public static SubjectConfig of(SchemaRecipe recipe) {
            return new SubjectConfig(Optional.ofNullable(recipe), Optional.empty(), Optional.empty(),
                Optional.empty(), LintConfig.empty(), SessionStateConfig.none());
        }
    }

    /**
     * Where a generating run wrote. One value because the three travel together: they are present
     * together on any generating run and jointly answer one question, which is the family's grain
     * rule ("joint presence and joint meaning") rather than three loose components.
     */
    public record OutputCoordinates(String outputPackage, String jooqPackage, Path outputDirectory) {
        public OutputCoordinates {
            Objects.requireNonNull(outputPackage, "outputPackage");
            Objects.requireNonNull(jooqPackage, "jooqPackage");
            Objects.requireNonNull(outputDirectory, "outputDirectory");
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
        runInternal(storeDirectory, graph, config, registry, assembly, verdicts, attribution, jooq,
            extensions, ClassifiedRun.absent());
    }

    /**
     * {@link #run}, then the store-backed detections over the store the capture just filled,
     * before it closes. Returns the {@link StoreDetections} product every family writes into
     * (gated on {@code reach}): every caller reads its
     * {@link StoreDetections#violations() violations} for the error stream, and
     * the LSP/MCP snapshot path additionally reads its
     * {@link StoreDetections#fieldConflicts() field conflicts} for the
     * {@code Conflicted} projection overlay; the store handle never escapes. The detections run
     * against whichever store the capture landed in, shared file and in-memory fallback alike,
     * so a cache demotion changes cost and never verdicts.
     */
    public static StoreDetections runWithDetections(Path storeDirectory, GraphIdentity graph,
                                                          SubjectConfig config,
                                                          TypeDefinitionRegistry registry,
                                                          SchemaAssembly assembly,
                                                          SdlVerdicts verdicts,
                                                          Map<String, SchemaInput> attribution,
                                                          JooqCatalog jooq,
                                                          List<CompletionData.ExternalReference> extensions,
                                                          ClassifiedRun classified) {
        Objects.requireNonNull(classified, "classified");
        return runInternal(storeDirectory, graph, config, registry, assembly, verdicts, attribution,
            jooq, extensions, classified);
    }

    private static StoreDetections runInternal(Path storeDirectory, GraphIdentity graph,
                                                     SubjectConfig config,
                                                     TypeDefinitionRegistry registry,
                                                     SchemaAssembly assembly, SdlVerdicts verdicts,
                                                     Map<String, SchemaInput> attribution, JooqCatalog jooq,
                                                     List<CompletionData.ExternalReference> extensions,
                                                     ClassifiedRun classified) {
        if (storeDirectory != null) {
            try (GraphitronModelStore store = GraphitronModelStore.openAt(storeDirectory)) {
                if (store.location().isEmpty()) {
                    // openAt already fell back to an in-memory store; use it as-is.
                    capture(store.dsl(), false, graph, config, registry, assembly, verdicts,
                        attribution, jooq, extensions);
                    return detect(store.dsl(), graph, classified);
                }
                if (!store.warm() || ownsGraph(store.dsl(), graph)) {
                    if (captureWithRetry(store, graph, config, registry, assembly, verdicts,
                            attribution, jooq, extensions)) {
                        return detect(store.dsl(), graph, classified);
                    }
                }
            }
        }
        try (GraphitronModelStore store = GraphitronModelStore.open()) {
            capture(store.dsl(), false, graph, config, registry, assembly, verdicts, attribution,
                jooq, extensions);
            return detect(store.dsl(), graph, classified);
        }
    }

    /**
     * The detection pass over a freshly captured store, dispatched on whether the run has a
     * classified model at all: {@link ClassifiedRun.Absent} is {@link #run}'s no-detection arm,
     * which also writes no {@code walk_} rows. The walk-side backing rows land first, because the
     * same pass is that family's one writer and its cadence, whether or not a detection reads them.
     *
     * <p>Nothing the detections read is gated on the walk any more. The authored-claim conflict
     * rule reads a relation that is total over the authored claims and applies the population its
     * own question needs (the classification domain, derived from captured SDL facts at capture
     * cadence), so its accept line is a fact of the store rather than of the walk's reach.
     *
     * <p>Beside the two detections the pass reads the {@code argMapping} family's positive half,
     * {@link ResolvedKeyProjections}, which the plan emits from. It is read here for the reason the
     * detections are: the store handle is this method's, and a phase that wanted the fact later would
     * have to reopen the store to ask.
     *
     * <p>The {@code argMapping} family reads only SDL facts and shares the classified-run arm
     * anyway: a run with no classified model is a run whose verdict has already been pronounced
     * elsewhere, and there is no build for these rejections to fail.
     */
    private static StoreDetections detect(DSLContext dsl, GraphIdentity graph,
                                          ClassifiedRun classified) {
        return switch (classified) {
            case ClassifiedRun.Absent ignored -> StoreDetections.empty();
            case ClassifiedRun.Present present -> {
                TypeBackingClassRows.write(dsl, graph.name(), present.backingClasses());
                yield new StoreDetections(AuthoredClaimConflicts.detect(dsl, graph.name()),
                    ArgmappingProjectionDefects.detect(dsl, graph.name()),
                    ResolvedKeyProjections.read(dsl, graph.name()));
            }
        };
    }

    /**
     * Attempts the warm capture, retrying once against the same store before giving up, so a
     * transient concurrency casualty (cleared by the time the retry runs) is told apart from a
     * deterministic capture bug (fails the same way both times). Returns {@code true} once either
     * attempt lands; {@code false} tells the caller to fall back to an in-memory capture instead.
     */
    private static boolean captureWithRetry(GraphitronModelStore store, GraphIdentity graph,
                                            SubjectConfig config, TypeDefinitionRegistry registry,
                                            SchemaAssembly assembly, SdlVerdicts verdicts,
                                            Map<String, SchemaInput> attribution, JooqCatalog jooq,
                                            List<CompletionData.ExternalReference> extensions) {
        try {
            capture(store.dsl(), store.warm(), graph, config, registry, assembly, verdicts,
                attribution, jooq, extensions);
            return true;
        } catch (DataAccessException first) {
            LOG.debug("shared fact store write failed; retrying once before recapturing in memory", first);
        }
        try {
            capture(store.dsl(), store.warm(), graph, config, registry, assembly, verdicts,
                attribution, jooq, extensions);
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
        dsl.transaction(tx -> {
            DSLContext txDsl = tx.dsl();
            var sink = new FactSink(txDsl, graph.name());
            var sources = new ClasspathSources();
            writeGraph(txDsl, sources, graph, config);
            if (warm) {
                StoreRefresh.prepare(sink, sources, extensions, graph.name());
            }
            ConfigurationFactCapture.capture(sink, config);
            var expansions = SdlFactCapture.capture(sink, registry, sources, attribution,
                verdicts.refusedSourceNames());
            SdlVerdictCapture.capture(sink, verdicts, assembly);
            CatalogFactCapture.capture(sink, jooq, extensions, sources);
            sink.flush();
            // The capture-cadence derivation stratum: materialized derivations re-derive from
            // the flushed rows inside the same transaction, so they are current exactly when
            // the partition they derive from is.
            ClassificationDomainCapture.derive(txDsl, graph.name(),
                assembly instanceof SchemaAssembly.Assembled a ? a.schema() : null,
                expansions.synthesizedEdges());
            InputOccurrencePaths.derive(txDsl, graph.name());
            TypeBackingRows.derive(txDsl, graph.name());
            sources.commitStamps(txDsl);
        });
    }

    /** SDL-only capture, for callers with no catalog in hand. */
    public static void capture(DSLContext dsl, GraphIdentity graph, SubjectConfig config,
                               TypeDefinitionRegistry registry, Map<String, SchemaInput> attribution) {
        capture(dsl, graph, config, registry, attribution, null, List.of());
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
