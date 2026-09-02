package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.command.GlobalCommand;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.rewrite.capture.CapturePort;
import no.sikt.graphitron.rewrite.capture.CaptureRequest;
import no.sikt.graphitron.rewrite.capture.GraphIdentity;
import no.sikt.graphitron.rewrite.capture.SubjectConfig;
import no.sikt.graphitron.rewrite.compile.CompileDependencyGraph;
import no.sikt.graphitron.rewrite.compile.PlanCompileGraph;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.derive.StoreDetections;
import no.sikt.graphitron.rewrite.derive.ClassifiedRun;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.lint.LintEngine;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.SchemaAssembly;
import no.sikt.graphitron.rewrite.schema.SdlVerdicts;
import no.sikt.graphitron.rewrite.schema.federation.KeyNodeSynthesiser;
import no.sikt.graphitron.rewrite.schema.input.DescriptionNoteApplier;
import no.sikt.graphitron.rewrite.schema.input.FederationLinkApplier;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.schema.input.TagApplier;
import no.sikt.graphitron.rewrite.schema.input.TagLinkSynthesiser;
import no.sikt.graphitron.rewrite.generators.schema.ConstraintViolationsClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.EnumTypeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ErrorMappingsClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronDevExecutorGenerator;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronFacadeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronSchemaClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.InputRecordGenerator;
import no.sikt.graphitron.rewrite.generators.schema.InputTypeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ObjectTypeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.SchemaSdlEmitter;
import no.sikt.graphitron.rewrite.generators.util.LightFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ErrorTypeFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionRuntimeClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronConnectionInstrumentationGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronTransactionProviderGenerator;
import no.sikt.graphitron.rewrite.generators.util.EntityFetcherDispatchClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronContextInterfaceGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.OneOfDirectiveSdlGenerator;
import no.sikt.graphitron.rewrite.generators.util.QueryNodeFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.OrderByResultClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.PolymorphicSelectionSetClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.SelectionOccurrencesClassGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Entry point for the rewrite code-generation pipeline.
 *
 * <p>Parses the GraphQL schema with {@link GraphitronSchemaBuilder}, runs its list of
 * generators, and writes output to the configured output directory.
 *
 * <p><b>One body, four projections.</b> {@link #runPipeline} is the whole pipeline and the only
 * place its stages are named: read and attribute the schema inputs, assemble and record the stage
 * verdicts, classify, load the jOOQ catalog, scan the classpath census, capture the graph's
 * partition, validate, lint, and (conditionally) project the completion catalog, emit the sources
 * and project the compile graph. Each public entry point is a {@link Projection} of that one call:
 * {@link #generate()}, {@link #validate()}, {@link #buildOutput()} and {@link #runPass()}. A pass
 * calls exactly one of them, which is what keeps a round to one capture of the graph.
 *
 * <p>A fifth entry point that grows a front half of its own is the regression this shape exists to
 * prevent: two bodies duplicating the stages above is what let one dev round capture the same
 * graph twice, and what let a stage added to one body go silently missing from the other. Add a
 * projection, not a pipeline.
 */
public class GraphQLRewriteGenerator {
    static final Logger LOGGER = LoggerFactory.getLogger(GraphQLRewriteGenerator.class);

    private static final List<String> OWNED_SUBPACKAGES =
        List.of("", "util", "schema", "types", "conditions", "fetchers", "inputs");

    private final RewriteContext ctx;
    private final CapturePort capture;

    /**
     * Constructs a generator driven by the supplied {@link RewriteContext}. The context's
     * {@code schemaInputs} drive schema loading; {@link TagApplier} and
     * {@link DescriptionNoteApplier} run between parse and classification.
     *
     * <p>Capture goes through {@link CapturePort#forContext}, which opens and closes a store
     * around each capture. For a caller that runs more than one pass, the constructor below takes
     * a port whose store outlives them.
     */
    public GraphQLRewriteGenerator(RewriteContext ctx) {
        this(ctx, CapturePort.forContext(ctx));
    }

    /**
     * Constructs a generator that captures through the caller's {@code capture} port, so the store
     * every pass writes into and reads back is the caller's to open, share between passes and
     * close. The Maven goals build one per invocation.
     *
     * <p>The port is the whole of what this class knows about the fact store: it never names one,
     * never learns where one lives, and cannot hold a handle past the reads it asked for. That is
     * the point of the parameter rather than a consequence of it, the generator being a reader of
     * facts and never a writer.
     */
    public GraphQLRewriteGenerator(RewriteContext ctx, CapturePort capture) {
        this.ctx = ctx;
        this.capture = Objects.requireNonNull(capture, "capture");
    }

    /**
     * Runs the full code-generation pipeline: loads and attributes schema inputs, classifies,
     * validates, and writes all generated sources to the configured output directory.
     *
     * <p>Returns the run's {@link GenerationResult}: every compilation unit emitted and the subset
     * whose on-disk content changed. The idempotent writer computes that delta per file (it writes
     * only on a content mismatch); surfacing it lets the incremental compile engine recompile the
     * changed sub-closure instead of the whole tree. Callers that only need the write-to-disk side
     * effect may ignore the return value.
     */
    public GenerationResult generate() {
        var pass = runPipeline(Projection.GENERATE);
        logWarnings(pass.warnings());
        logErrors(pass.errors());
        if (!pass.errors().isEmpty()) {
            throw new ValidationFailedException(pass.errors());
        }
        return pass.generation().result();
    }

    /**
     * The dev loop's pass: the only projection that unions the emitting and the reporting halves,
     * so one round of {@code graphitron:dev} is one generator run and one capture of the graph.
     * Emits every source, projects the {@link CompileDependencyGraph} the incremental compile
     * driver needs to compute the per-save recompile set, and produces the editor-facing
     * {@link BuildOutput} beside them. The graph is projected from the same {@link EmitPlan} this
     * run rendered from, so it is always consistent with the sources just written.
     *
     * <p>Never throws on a validator verdict: a rejected round returns its errors on
     * {@code output().report()} and an absent generation, which is what lets the same round both
     * refuse to emit and tell the editor why. Warnings are logged here (the dev console wants
     * them); the errors are not, the dev loop rendering {@code WatchErrorFormatter}'s grouped tree
     * from the returned report instead of a line per error.
     */
    public Pass runPass() {
        var pass = runPipeline(Projection.PASS);
        logWarnings(pass.warnings());
        return new Pass(pass.output(), Optional.ofNullable(pass.generation()));
    }

    /**
     * One dev pass's two halves: the editor-facing {@link BuildOutput} (completion catalog plus the
     * diagnostics the store's stratum is written from) and the {@link IncrementalGeneration} the
     * compile driver reads.
     *
     * <p>The generation is present exactly when {@code output().report().errors()} is empty: a
     * rejected schema emits nothing and reports everything.
     */
    public record Pass(BuildOutput output, Optional<IncrementalGeneration> generation) {}

    /**
     * A {@link #runPass()} run's emitted half: the {@link GenerationResult} (emitted set + writer
     * delta + emitted {@link TypeSpec}s) paired with the {@link CompileDependencyGraph} projected from
     * the same plan. Together these are the raw material the dev-loop compile driver
     * reads: the graph and the ABI hashes derived from {@code result.emittedUnits()} decide which units a
     * save must recompile.
     */
    public record IncrementalGeneration(GenerationResult result, CompileDependencyGraph graph) {}

    /**
     * The result of a generation run: {@code emitted} is every source and resource path written or
     * confirmed this run (the orphan-sweep survivor set), {@code changed} is the subset of generated
     * {@code .java} compilation units whose content differed from disk and was (re)written. The
     * emitted SDL resource is reported in {@code emitted} but never in {@code changed}: it is not a
     * compilation unit, so it never feeds the recompile set.
     *
     * <p>{@code emittedUnits} and {@code changedUnits} are the same two sets keyed by fully-qualified
     * class name rather than path, and {@code emittedUnits} additionally carries the emitted
     * {@link TypeSpec} per unit. This is the raw material the incremental compile driver reads: the
     * FQCN keys are the graph's node identities (and the live set for the {@code .class} orphan sweep),
     * the {@link TypeSpec} values feed {@code AbiSignature.hash} for ABI-vs-body discrimination, and
     * {@code changedUnits} is the writer's delta by FQCN. Hashing is left to the consumer, so a
     * production {@code generate()} that ignores the result pays no ABI-hashing cost; retaining the
     * specs is a transient reference the run discards. The SDL resource is not a compilation unit and
     * appears in neither map.
     *
     * <p>{@code plan} is the {@link EmitPlan} the run rendered from, whole: every relation the
     * producers minted, never a re-derivation. The bidirectional closure oracle joins
     * {@code plan().launchers()} against {@code emittedUnits}, and the recompile graph is
     * projected from the same plan, so both read what the run actually rendered from.
     */
    public record GenerationResult(Set<Path> emitted, Set<Path> changed,
                                   Map<String, TypeSpec> emittedUnits, Set<String> changedUnits,
                                   EmitPlan plan) {}

    /**
     * Mutable per-run accumulator for the emitted set and the changed-file delta, tracked both by path
     * (for the {@code .java} sweep) and by FQCN (for the compile graph / recompile set). Kept internal;
     * {@link #runPipeline} converts it to the immutable {@link GenerationResult} it returns.
     */
    private static final class EmissionLog {
        private final Set<Path> emitted = new LinkedHashSet<>();
        private final Set<Path> changed = new LinkedHashSet<>();
        private final Map<String, TypeSpec> emittedUnits = new LinkedHashMap<>();
        private final Set<String> changedUnits = new LinkedHashSet<>();

        /**
         * Records a compilation-unit write, folding its {@code changed} flag into both deltas.
         * A duplicate landing address is a hard failure: two families writing one FQCN would
         * clobber silently (second write wins) and surface as a missing-symbol error at the
         * consumer's javac, the failure class this guard exists to remove.
         */
        void record(String fqcn, TypeSpec spec, JavaFile.WriteResult result) {
            emitted.add(result.path());
            if (emittedUnits.putIfAbsent(fqcn, spec) != null) {
                throw new IllegalStateException(
                    "two generators landed a unit at '" + fqcn + "' in one run; the second write"
                    + " would silently clobber the first and fail at the consumer's compile");
            }
            if (result.changed()) {
                changed.add(result.path());
                changedUnits.add(fqcn);
            }
        }

        /** Records an emitted path (e.g. the SDL resource) as present without touching the delta. */
        void add(Path path) {
            emitted.add(path);
        }
    }

    /**
     * The editor-facing products with nothing emitted: the {@link CompletionData} catalog (jOOQ +
     * classpath references + scalars) over the same census the capture wrote its classpath families
     * from, and the {@link ValidationReport} carrying every {@link ValidationError} and
     * {@link BuildWarning} the validator produces on the classified model. Same parse throughout;
     * the dev goal writes the report's two pre-fuse lists to the store's diagnostics stratum, which
     * is where the language server reads them.
     *
     * <p>Two callers, both of which want the report without a tree: a consumer {@code .class}
     * change (a catalog question, not a generation one) and a dev startup that was told to emit
     * nothing. A round that does emit takes {@link #runPass()} instead, which returns this same
     * output beside the generation; asking for both by calling both is what captured the graph
     * twice.
     *
     * <p>The validator runs but never throws on its output: a half-edited
     * buffer with validation errors should still expose tables and scalars
     * so the editor can autocomplete its way out of the typo. The build-time
     * pipeline ({@link #validate()}, {@link #generate()}) is the surface
     * that fails the build on validator errors; this method packages them
     * for the LSP instead.
     */
    public BuildOutput buildOutput() {
        return runPipeline(Projection.BUILD_OUTPUT).output();
    }

    /**
     * Splits a pass's reporting half along the two lifecycle steps it spans: classification
     * produces the {@link CompletionData} catalog over the census the capture also wrote from; the
     * validator pass over the same classified model produces {@link ValidationReport}. Returned by
     * {@link #buildOutput()} on its own and by {@link #runPass()} beside the generation, both from
     * the one body, so the two cannot disagree about a round.
     *
     * <p>The two pre-fuse lists ride alongside the fused report for the diagnostics-stratum
     * loaders, each carrying a partition the report cannot express once fused:
     * {@code walkErrors} is the walk's own error stream before the detection violations are
     * appended (so a detection-minted family is structurally absent from the residue loader's
     * input), and {@code warnings} is the suppression-filtered list the report was assembled
     * from (so stored lint rows are post-suppression survivors, never resurrected findings).
     */
    public record BuildOutput(CompletionData catalog, ValidationReport report,
                              List<ValidationError> walkErrors, List<BuildWarning> warnings) {}

    /**
     * Runs schema loading, attribution, classification, and validation without writing any output.
     * Throws {@link ValidationFailedException} if validation errors are found.
     */
    public void validate() {
        var pass = runPipeline(Projection.VALIDATE);
        logWarnings(pass.warnings());
        logErrors(pass.errors());
        if (!pass.errors().isEmpty()) {
            throw new ValidationFailedException(pass.errors());
        }
    }

    /**
     * Package-private so tests can exercise the attribution + load + apply
     * pipeline without incurring the full emission stage. Production callers
     * always go through {@link #generate()}.
     *
     * <p>Returns the loaded {@link AttributedRegistry} carrying both the
     * {@link graphql.schema.idl.TypeDefinitionRegistry} and the federation
     * {@code injectedNames} captured from {@link FederationLinkApplier#apply}'s
     * return value (the {@code federationLink} flag is derived from it), so
     * downstream stages read both without re-walking the registry.
     */
    AttributedRegistry loadAttributedRegistry() {
        return loadAttributedRegistry(new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()));
    }

    /**
     * {@link #loadAttributedRegistry()} over a jOOQ catalog the pass already loaded. The catalog is
     * only reached when the federation {@code @link} injector produced names, {@code @key}
     * synthesis resolving its node declarations against it; taking it as a parameter is what keeps
     * a pass to one load of the generated classes rather than one per stage that wants them.
     */
    private AttributedRegistry loadAttributedRegistry(JooqCatalog jooq) {
        var bySource = SchemaInputAttribution.build(ctx.schemaInputs());
        // Read every source, refusing none of them on another's behalf, and carry the refusals
        // rather than throwing on them. A source that will not parse costs its own declarations and
        // nothing else, and a declaration the registry will not admit costs itself; the run's
        // verdict on those refusals is pronounced downstream, after they have been recorded, so a
        // freshly broken file cannot blank the facts about every file beside it.
        var read = RewriteSchemaLoader.parsePerSource(loadableSources(ctx.schemaInputs()));
        var registry = read.registry();
        TagLinkSynthesiser.apply(registry, bySource);
        var injectedNames = FederationLinkApplier.apply(registry);
        TagApplier.apply(registry, bySource);
        DescriptionNoteApplier.apply(registry, bySource);
        // Everything above is a loading rewrite and everything below is synthesis, which is the
        // line the capture handle is cut on. TagApplier and DescriptionNoteApplier sit above it
        // deliberately: their @tag applications and appended notes are in the emitted schema, and
        // the store owes a round trip, so capture has to see them. They used to sit below
        // KeyNodeSynthesiser, which changed nothing about the registry either applier produces
        // (neither touches the directive list KeyNodeSynthesiser rewrites) but did decide, by
        // accident of ordering, what a capture cut here would see.
        var preSynthesis = registry.readOnly();
        if (!injectedNames.isEmpty()) {
            KeyNodeSynthesiser.apply(registry, new NodeDeclaration(jooq));
        }
        return new AttributedRegistry(registry, preSynthesis, injectedNames, read);
    }

    /**
     * Runs the assembly stage and records every stage's verdict, then pronounces the run's own
     * verdict on them.
     *
     * <p>Assembly is where the GraphQL specification's structural rules are checked at all, so it
     * runs on every pass whether or not this pass has any use for the assembled schema, and its
     * outcome is written down either way. The order is the point: capture first, fail second. The
     * three read stages are a pipeline in that each consumes what the last produced, never in the
     * sense that an earlier refusal cancels a later stage, and the recording is what would be lost
     * by failing at the first refusal instead of the last.
     *
     * <p>Returns the assembled schema paired with the stage verdicts, having thrown if any stage
     * refused: a refusal is still fatal to a build, and each stage still throws exactly the
     * exception it always threw, so the mojo's catch arms and the dev loop's one-line parse report
     * are unchanged. The verdicts ride along so the pass that goes on to classify records the same
     * emptiness this method would have recorded, derived from the stages rather than assumed.
     */
    private ReadSchema assembleAndCaptureVerdicts(AttributedRegistry attributed, JooqCatalog jooq,
                                                  List<CompletionData.ExternalReference> census) {
        // The assembly that judges the document is the one over the registry the store transcribes,
        // before the synthesis rewrites. Judging the post-synthesis registry instead let a verdict
        // blame the author for a declaration graphitron's own rewrite injected; a verdict is a fact
        // about what the author wrote, so it comes from the same registry the facts do.
        var assembly = SchemaAssembly.of(attributed.preSynthesisRegistry());
        var verdicts = SdlVerdicts.of(attributed.read());
        if (verdicts.anyRefusal() || !assembly.errors().isEmpty()) {
            captureFacts(attributed, assembly, verdicts, jooq, census);
            RewriteSchemaLoader.throwIfRejected(attributed.read());
            // Nothing above threw, so the refusal was assembly's own: rethrow it as the stage
            // raised it, which is the exception this path has always failed with.
            GraphitronSchemaBuilder.assembleOrFail(assembly);
        }
        var pipeline = assemblyForPipeline(attributed, assembly);
        if (!(pipeline instanceof SchemaAssembly.Assembled)) {
            // graphitron's own rewrite broke a document the author wrote correctly, the assembly
            // above having succeeded on the registry the store transcribes. Capture from that
            // assembly before failing: the author's facts are all still true, and withholding them
            // is the "one broken thing blanks every fact beside it" failure this file argues
            // against, here caused by our own defect rather than by anything they wrote. The
            // verdicts written are still the pre-synthesis ones, so no ASSEMBLY row blames them.
            captureFacts(attributed, assembly, verdicts, jooq, census);
        }
        return new ReadSchema(GraphitronSchemaBuilder.assembleOrFail(pipeline), verdicts, assembly);
    }

    /**
     * The assembly the pipeline classifies, which is over the post-synthesis registry: the
     * federation key and node declarations {@link KeyNodeSynthesiser} injected are part of what the
     * generator emits. A second assembly is paid for only when that rewrite ran at all; with no
     * injected names the pre-synthesis registry is the same document, so the assembly above is
     * reused and this can only be its {@code Assembled} arm.
     *
     * <p>Returned as the outcome value rather than assembled-or-thrown, because a refusal here is
     * graphitron's own defect and the caller has something to do about it before failing.
     */
    private static SchemaAssembly assemblyForPipeline(AttributedRegistry attributed,
                                                      SchemaAssembly preSynthesis) {
        return attributed.injectedNames().isEmpty()
            ? preSynthesis
            : SchemaAssembly.of(attributed.registry());
    }

    /**
     * A successfully read schema: what assembly produced, and what the three stages said about the
     * document on the way. The verdicts are empty by construction here, every refusal having been
     * fatal upstream, but they are carried rather than re-synthesised so the capture downstream
     * writes a fact about this read instead of a constant.
     */
    private record ReadSchema(GraphQLSchema assembled, SdlVerdicts verdicts,
                              SchemaAssembly preSynthesisAssembly) {}

    /**
     * The run's inputs projected onto what the loader can open. The switch is checked for coverage
     * rather than for absence: no context in the tree carries a label this far, so the named branch
     * is a guard rather than a live path, and its value is that a new source kind cannot silently
     * shorten the schema. A label reaching a pipeline run keeps the loader's own
     * "Schema file not found", which is what it produced before the parameter narrowed.
     */
    private static List<SchemaSource.File> loadableSources(List<SchemaInput> inputs) {
        var sources = new ArrayList<SchemaSource.File>(inputs.size());
        for (SchemaInput input : inputs) {
            switch (input.source()) {
                case SchemaSource.File file -> sources.add(file);
                case SchemaSource.Named named ->
                    throw new RuntimeException("Schema file not found: " + named.label());
            }
        }
        return sources;
    }

    /**
     * Runs the capture loads into a fact store for this pass, runs the store-backed detections over
     * it, and hands the caller's continuation the open store plus the {@link StoreDetections}
     * product the detections share: the violations for the pass's error stream, and the
     * field-conflict claims the LSP/MCP snapshot's {@code Conflicted} projection overlay consumes.
     * Three families read the store here.
     * The authored-claim conflict rule reports from the claim views over the classification
     * domain, a captured-fact population rather than anything the walk reached; the walked model
     * contributes nothing to this seam now, the {@link ClassifiedRun} arm being a property of
     * which path reached here rather than of anything the walk resolved. The two
     * {@code @nodeId} rules
     * ({@link no.sikt.graphitron.rewrite.derive.ArgmappingProjectionDefects} for a node id an
     * {@code argMapping} entry binds, {@link no.sikt.graphitron.rewrite.derive.NodeIdDecodeDefects}
     * for one a producer parameter's name receives) report from the captured corpora alone and are
     * gated on nothing of the walk's. Every other relation still shadows the
     * live pipeline unread, kept honest by the agreement tests until its own consumer migrates.
     *
     * <p>Both loads read exactly what the pipeline beside them reads: the parsed registry (before
     * the synthesis rewrites, which is what {@link AttributedRegistry#preSynthesisRegistry()}
     * hands back), the jOOQ catalog projection, and the classpath scan. Both arrive as parameters
     * from the top of {@link #runPipeline}, so the census the store's classpath families are
     * written from is the same scan the completion catalog projects and the same one, per pass.
     *
     * <p>The caller's own reads run inside the capture's window: the store stays open past the
     * detections so the plan can question the same facts the capture just wrote, instead of a
     * producer reopening the store or being handed a value someone else read for it. This is the
     * class's one capture seam; nothing else opens a store for a classified run.
     */
    private <T> T captureAndRead(
            AttributedRegistry attributed, ReadSchema read,
            JooqCatalog jooq, List<CompletionData.ExternalReference> extensions,
            CapturePort.AfterCapture<T> after) {
        return capture.captureAndRead(
            request(attributed, read.preSynthesisAssembly(), read.verdicts(), jooq, extensions,
                ClassifiedRun.present()),
            after);
    }

    /**
     * The capture with no classified model to gate detections on: the arm the failure paths take,
     * where a stage refused the document and there is no walk to derive a claim domain from. It
     * writes everything the surviving declarations support plus the stages' verdicts, which is the
     * whole point of running it here rather than giving up.
     */
    private void captureFacts(AttributedRegistry attributed, SchemaAssembly assembly,
                              SdlVerdicts verdicts, JooqCatalog jooq,
                              List<CompletionData.ExternalReference> census) {
        capture.capture(
            request(attributed, assembly, verdicts, jooq, census, ClassifiedRun.absent()));
    }

    /**
     * This pass's capture, as the one value both arms above build. Assembled here so the two
     * cannot describe the same pass differently: the failure arm and the classified arm used to
     * spell the same nine arguments at two call sites, which is how the registry each of them
     * handed over came to be chosen twice.
     */
    private CaptureRequest request(AttributedRegistry attributed, SchemaAssembly assembly,
                                   SdlVerdicts verdicts, JooqCatalog jooq,
                                   List<CompletionData.ExternalReference> census,
                                   ClassifiedRun classified) {
        return new CaptureRequest(graphIdentity(), subjectConfig(),
            attributed.preSynthesisRegistry(), assembly, verdicts,
            SchemaInputAttribution.build(ctx.schemaInputs()), jooq, census, classified);
    }

    /** The coordinate this run writes under, assembled from the context's identity fields. */
    private GraphIdentity graphIdentity() {
        return new GraphIdentity(ctx.graphName(), ctx.basedir());
    }

    /**
     * The configuration capture transcribes about this run's graph. Assembled here rather than
     * carried on the coordinate: a caller with no configuration to declare has none to synthesise.
     */
    private SubjectConfig subjectConfig() {
        return new SubjectConfig(
            Optional.ofNullable(ctx.schemaRecipe()),
            Optional.ofNullable(ctx.supergraph()),
            ctx.declaredOutputCoordinates(),
            Optional.ofNullable(ctx.tenantColumn()),
            ctx.lintConfig(),
            ctx.sessionStateConfig());
    }

    /**
     * Which of the body's three optional halves one entry point wants. The four constants are the
     * four public entry points, stated here rather than spelled at each call so the difference
     * between the projections is one table a reader can hold at once. Everything not switched on
     * here runs on every pass, because every pass needs it: one read, one assembly, one
     * classification, one jOOQ load, one census, one capture, one validator run, one lint run.
     *
     * @param emit        run the plan, the renderers, the writer, the orphan sweep and the SDL resource
     * @param compileGraph project the {@link CompileDependencyGraph} the dev compile driver reads
     * @param catalog     project the {@link CompletionData} completion catalog
     */
    private record Projection(boolean emit, boolean compileGraph, boolean catalog) {
        /** {@code GenerateMojo}: the emitted tree and nothing the dev loop or the editor wants. */
        static final Projection GENERATE = new Projection(true, false, false);

        /** {@code ValidateMojo}: the verdict alone, no output of any kind. */
        static final Projection VALIDATE = new Projection(false, false, false);

        /** The editor-facing products with no emission: catalog and diagnostics. */
        static final Projection BUILD_OUTPUT = new Projection(false, false, true);

        /** The dev loop's pass: both halves at once, which is the whole point of it. */
        static final Projection PASS = new Projection(true, true, true);
    }

    /**
     * What one pass computed, before an entry point decides what to do with it. The generation is
     * null exactly when this pass did not emit: either the projection asked for no emission, or the
     * validator rejected the schema and there was nothing to emit. The catalog is null when the
     * projection asked for no catalog, so only the two projections that asked for one may call
     * {@link #output()}.
     */
    private record PassProducts(CompletionData catalog, List<ValidationError> walkErrors,
                                List<ValidationError> errors, List<BuildWarning> warnings,
                                IncrementalGeneration generation) {

        /** The editor-facing projection of these products, fusing the report the LSP reads. */
        BuildOutput output() {
            return new BuildOutput(catalog, ValidationReport.from(errors, warnings),
                walkErrors, warnings);
        }
    }

    /**
     * What the capture window produced: the walk's own error stream, that stream fused with the
     * store-backed detections' violations, and the plan, or null where the errors stopped it (or
     * the projection wanted no emission). A value rather than a throw, because the products the
     * caller wants beside the verdict are computed by the time it is pronounced, and a validated
     * schema and a rejected one differ only in whether a plan came back.
     */
    private record Captured(List<ValidationError> walkErrors, List<ValidationError> errors,
                            EmitPlan plan) {}

    /**
     * The pipeline. Every public entry point runs this body and projects what it wants out of the
     * result; see the class javadoc for why there is one body rather than one per entry point.
     */
    private PassProducts runPipeline(Projection projection) {
        // The two whole-classpath reads, hoisted above every stage that wants them so a pass pays
        // for each exactly once: the generated jOOQ classes (loaded by reflection, and holding the
        // per-table caches every later lookup reads) and the classpath census (a scan and parse of
        // every consumer class). Both feed @key synthesis, the capture, the completion catalog and
        // the detections, which used to load one apiece.
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var census = CatalogBuilder.buildExternalReferences(ctx);

        var attributed = loadAttributedRegistry(jooq);
        var read = assembleAndCaptureVerdicts(attributed, jooq, census);
        var bundle = GraphitronSchemaBuilder.buildBundle(attributed, read.assembled(), ctx);
        var schema = bundle.model();
        var assembled = bundle.assembled();
        boolean federationLink = bundle.federationLink();

        var catalog = projection.catalog()
            ? CatalogBuilder.build(jooq, assembled, ctx, census)
            : null;
        // Computed here, logged by the entry point that wants them: the one-shot build goals emit a
        // line per warning, the dev loop emits the same lines, and buildOutput() is silent because
        // its consumer reads them off the store rather than the console.
        var warnings = withLintFindings(schema, attributed);

        String outputPackage = ctx.outputPackage();

        // Capture, validate and plan share one open store, and the order between them is this
        // method's to state. Capture runs ahead of validation because the store-backed detections
        // feed the error stream, so the store has to be filled before the verdict is pronounced;
        // the plan runs after it because a producer reads what this run emits, and there is no
        // emission for a schema validation rejected. The plan is also produced before the per-type
        // generators run: the launcher relation's rows are read by the fetcher generator (a root
        // coordinate with a row gets the launcher emission, one without falls through to its
        // legacy builder), and those generators need no store, so the window closes here.
        var captured = captureAndRead(attributed, read, jooq, census,
            (store, storeFacts) -> {
                // The handle is what the window exists to hand over, and the plan tier holds it:
                // the producers convert onto the store one family at a time, and each conversion
                // is a parameter change inside the plan rather than a lifecycle one here. The
                // routine-write relation is the first that reads it.
                var walkErrors = List.copyOf(new GraphitronSchemaValidator().validate(schema));
                var fused = new ArrayList<>(walkErrors);
                fused.addAll(storeFacts.violations());
                var errors = List.copyOf(fused);
                if (!errors.isEmpty() || !projection.emit()) {
                    return new Captured(walkErrors, errors, null);
                }
                return new Captured(walkErrors, errors,
                    EmitPlan.produce(schema, federationLink, bundle.usesOneOf(), outputPackage,
                        storeFacts.keyProjections(), store));
            });

        if (captured.plan() == null) {
            return new PassProducts(catalog, captured.walkErrors(), captured.errors(), warnings, null);
        }
        var plan = captured.plan();

        var fetcherClasses = TypeFetcherGenerator.generate(schema, assembled, outputPackage,
            plan.launchers(), plan.typeUnits().fetchers(), plan.typeUnits().errorFetchers(),
            plan.routineWrites(),
            plan.keyProjections());
        // registerFetchers bodies render from the schema-shape rows' registersFetchers flag,
        // the same fact the per-type emitter and the schema-class assembler read.
        var fetcherBodies  = FetcherRegistrationsEmitter.emit(schema, outputPackage, plan.typeUnits().schemaShapes());

        EmissionLog emittedThisRun = new EmissionLog();
        // The tenant key type read off the catalog's tenant column types every tenant-keyed
        // runtime surface when <tenantColumn> is configured; null keeps the erased Object shape.
        var tenantKeyType = schema.tenantScopes() instanceof no.sikt.graphitron.rewrite.model.TenantScopes.Configured configuredTenancy
            ? configuredTenancy.tenantType()
            : null;

        // The global command relation: the core decided the family membership (the federation
        // @oneOf gate, the entity-dispatch, node-fetcher and dev-executor gates, the session-hook
        // unit) when it produced the plan; the shell folds over the rows, rendering each family
        // and landing every unit at the address its row committed. What the fold does not absorb
        // stays deliberately shell-side: per-family argument assembly (including tenantKeyType,
        // a javapoet TypeName the plan must not hold), and the generators' own model reads. The
        // per-type-emitting families below fold over the type-unit relation's rows the same way.
        for (GlobalCommand command : plan.globals()) {
            writeCommand(command,
                renderGlobal(command, schema, assembled, plan.typeUnits().schemaShapes(), tenantKeyType, federationLink),
                emittedThisRun);
        }
        // The condition command relation: every row renders glue at the address its ref commits,
        // and every WHERE consumer calls it (call-site convergence closed the render-side dial).
        writeUnits("condition glue",
            plan.conditions().units(),
            no.sikt.graphitron.render.ConditionGlueRenderer.render(plan.conditions().rows(), outputPackage,
                plan.keyProjections()),
            emittedThisRun);
        // The projection command relation: one $project unit per row (anchor types, anchor-prefixed
        // nesting units, per-coordinate pivot units), each landing at the address its row committed.
        writeUnits("projection units",
            plan.projections().units(),
            no.sikt.graphitron.render.ProjectionUnitRenderer.render(plan.projections().rows(), outputPackage),
            emittedThisRun);

        // The type-unit relation's input-record rows: membership (the argument-reachability
        // closure intersected with the record-shape capability) was decided by the producer;
        // the shell renders one class per row and lands it at the committed ref.
        writeUnits("input records",
            plan.typeUnits().inputRecordUnits(),
            plan.typeUnits().inputRecords().stream()
                .map(row -> InputRecordGenerator.generateFor(schema.type(row.typeName()), outputPackage))
                .toList(),
            emittedThisRun);

        // The type-unit relation's fetchers rows: one fold for the whole family (the hosting
        // classifications and nested classes rendered above through the fetcher generator's
        // per-row build, the connection pairs rendered per row here), landed at the committed
        // refs under writeUnits' two-directional unit-set check.
        var fetcherSpecs = new java.util.ArrayList<>(fetcherClasses);
        plan.typeUnits().connectionFetchers().forEach(row ->
            fetcherSpecs.addAll(ConnectionFetcherClassGenerator.generateFor(
                (no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType) schema.type(row.typeName()),
                outputPackage)));
        writeUnits("fetchers", plan.typeUnits().fetchersUnits(), fetcherSpecs, emittedThisRun);

        // The type-unit relation's schema-shape rows: one <Name>Type class per row, membership
        // and form decided by the producer's total switch over the classification permits; the
        // shell dispatches each row to its form's renderer and lands the class at the committed
        // ref. The registerFetchers body rides the row's flag (null for unflagged rows).
        writeUnits("schema shapes",
            plan.typeUnits().schemaShapeUnits(),
            plan.typeUnits().schemaShapes().stream()
                .map(row -> switch (row.form()) {
                    case ENUM -> EnumTypeGenerator.generateFor(
                        (no.sikt.graphitron.rewrite.model.GraphitronType.EnumType) schema.type(row.typeName()));
                    case INPUT -> InputTypeGenerator.generateFor(
                        (no.sikt.graphitron.rewrite.model.GraphitronType.InputType) schema.type(row.typeName()));
                    case OBJECT, INTERFACE, UNION -> ObjectTypeGenerator.generateFor(
                        schema, assembled, row, fetcherBodies.get(row.typeName()));
                })
                .toList(),
            emittedThisRun);
        emittedThisRun.add(SchemaSdlEmitter.emit(assembled, schema, federationLink, ctx.outputResourcesDirectory(), outputPackage));
        sweepOrphans(emittedThisRun.emitted);
        var result = new GenerationResult(
            Collections.unmodifiableSet(emittedThisRun.emitted),
            Collections.unmodifiableSet(emittedThisRun.changed),
            Collections.unmodifiableMap(emittedThisRun.emittedUnits),
            Collections.unmodifiableSet(emittedThisRun.changedUnits),
            plan
        );
        // Only the dev-loop incremental compiler needs the compile-dependency graph; production
        // generate() skips the build. See the sourcing seam in CompileDependencyGraph: the graph
        // is projected from the same plan this run rendered from, so it is always consistent
        // with the sources just written.
        CompileDependencyGraph graph = projection.compileGraph()
            ? PlanCompileGraph.fromPlan(plan, schema)
            : null;
        return new PassProducts(catalog, captured.walkErrors(), captured.errors(), warnings,
            new IncrementalGeneration(result, graph));
    }

    /**
     * The typed interpreter over the global command relation: one renderer invocation per
     * {@link no.sikt.graphitron.command.GlobalUnitKind}, total over the enum with no default arm,
     * so a new kind is a compile error here rather than a silently unrendered row. The generators
     * it dispatches to still take the model; they migrate family by family.
     */
    private List<TypeSpec> renderGlobal(GlobalCommand command, GraphitronSchema schema, GraphQLSchema assembled,
                                        List<no.sikt.graphitron.command.TypeUnitCommand.SchemaShapeUnit> schemaShapeRows,
                                        TypeName tenantKeyType, boolean federationLink) {
        String outputPackage = ctx.outputPackage();
        return switch (command.kind()) {
            case GRAPHITRON_VALUES -> GraphitronValuesClassGenerator.generate();
            case LIGHT_FETCHER -> LightFetcherClassGenerator.generate(outputPackage);
            case NODE_ID_ENCODER -> NodeIdEncoderClassGenerator.generate(schema);
            case ENTITY_FETCHER_DISPATCH -> EntityFetcherDispatchClassGenerator.generate(schema, outputPackage);
            case CONNECTION_RESULT -> ConnectionResultClassGenerator.generate(outputPackage, tenantKeyType != null);
            case CONNECTION_HELPER -> ConnectionHelperClassGenerator.generate(outputPackage, tenantKeyType != null);
            case ONE_OF_DIRECTIVE_SDL -> OneOfDirectiveSdlGenerator.generate(outputPackage);
            case POLYMORPHIC_SELECTION_SET -> PolymorphicSelectionSetClassGenerator.generate();
            case SELECTION_OCCURRENCES -> SelectionOccurrencesClassGenerator.generate(outputPackage);
            case ORDER_BY_RESULT -> OrderByResultClassGenerator.generate();
            case GRAPHITRON_CONTEXT -> GraphitronContextInterfaceGenerator.generate();
            case CONNECTION_RUNTIME -> ConnectionRuntimeClassGenerator.generate(outputPackage, schema.sessionHooks(), tenantKeyType);
            case TRANSACTION_PROVIDER -> GraphitronTransactionProviderGenerator.generate(outputPackage);
            case CONNECTION_INSTRUMENTATION -> GraphitronConnectionInstrumentationGenerator.generate(outputPackage, tenantKeyType != null, schema.sessionHooks());
            case CONSTRAINT_VIOLATIONS -> ConstraintViolationsClassGenerator.generate();
            case CLIENT_EXCEPTION -> GraphitronClientExceptionClassGenerator.generate();
            case ERROR_ROUTER -> ErrorRouterClassGenerator.generate(outputPackage);
            case OUTCOME -> OutcomeClassGenerator.generate(outputPackage);
            case ERROR_MAPPINGS -> ErrorMappingsClassGenerator.generate(schema, outputPackage);
            case SCHEMA_CLASS -> GraphitronSchemaClassGenerator.generate(schema, assembled, schemaShapeRows, outputPackage, federationLink);
            case QUERY_NODE_FETCHER -> QueryNodeFetcherClassGenerator.generate(schema, outputPackage);
            case FACADE -> GraphitronFacadeGenerator.generate(schema, outputPackage, federationLink);
            case DEV_EXECUTOR -> GraphitronDevExecutorGenerator.generate(schema, outputPackage, schema.sessionHooks(), federationLink);
        };
    }

    private void writeCommand(GlobalCommand command, List<TypeSpec> specs, EmissionLog emittedThisRun) {
        writeUnits("global command " + command.kind(), command.units(), specs, emittedThisRun);
    }

    /**
     * Writes one command family's rendered units at the addresses the plan committed. The
     * {@link UnitRef} is the single naming derivation: each spec lands at the ref carrying its
     * simple name, a spec no ref names has nowhere to go, and a committed ref no spec matched is
     * a dropped unit; both fail the run loudly instead of drifting into the compile graph as a
     * silent gap.
     */
    private void writeUnits(String family, List<UnitRef> units, List<TypeSpec> specs, EmissionLog emittedThisRun) {
        var refsByName = new LinkedHashMap<String, UnitRef>();
        for (UnitRef ref : units) {
            refsByName.put(ref.simpleName(), ref);
        }
        for (TypeSpec spec : specs) {
            UnitRef ref = refsByName.remove(spec.name());
            if (ref == null) {
                throw new IllegalStateException(
                    family + " emitted unit '" + spec.name()
                        + "' that the plan did not commit; the producer and the generator disagree"
                        + " about this family's unit set");
            }
            try {
                var result = JavaFile.builder(ref.packageName(), spec).indent("    ").build()
                    .writeToPathReporting(ctx.outputDirectory(), StandardCharsets.UTF_8);
                emittedThisRun.record(ref.fqcn(), spec, result);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (!refsByName.isEmpty()) {
            throw new IllegalStateException(
                family + " committed units " + refsByName.keySet()
                    + " that its renderer never emitted; the producer and the generator disagree"
                    + " about this family's unit set");
        }
    }

    private void sweepOrphans(Set<Path> emittedThisRun) {
        String outputPackage = ctx.outputPackage();
        Path outputDir = ctx.outputDirectory();
        for (String sub : OWNED_SUBPACKAGES) {
            String pkgName = sub.isEmpty() ? outputPackage : outputPackage + "." + sub;
            Path pkgDir = outputDir;
            for (String segment : pkgName.split("\\.")) {
                pkgDir = pkgDir.resolve(segment);
            }
            if (!Files.isDirectory(pkgDir)) continue;
            try (Stream<Path> files = Files.list(pkgDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                     .filter(p -> !emittedThisRun.contains(p))
                     .forEach(p -> {
                         try {
                             Files.delete(p);
                             LOGGER.info("Rewrite: swept orphan: {}", p);
                         } catch (IOException e) {
                             throw new RuntimeException(e);
                         }
                     });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Classification advisories ({@code schema.warnings()}) plus the SDL lint engine's findings
     * over the same parsed registry. Lint findings ride the {@link BuildWarning} channel here at
     * the report-assembly surfaces rather than inside {@link GraphitronSchemaBuilder}, so the
     * per-build classifier model stays advisory-only and only the user-facing report carries the
     * lint surface.
     */
    private List<BuildWarning> withLintFindings(GraphitronSchema schema, AttributedRegistry attributed) {
        LintConfig lintConfig = ctx.lintConfig();
        var all = new java.util.ArrayList<BuildWarning>(schema.warnings());
        // excludedTypes widens the engine's per-type skip; injectedNames excludes the
        // federation @link injector's generator-owned definitions at the same boundary.
        all.addAll(LintEngine.builtIn(lintConfig.excludedTypePatterns())
            .run(attributed.registry(), attributed.injectedNames()));
        // Codegen-config advisories about the owned-connection runtime's identity posture, derived
        // from the <sessionState> config. Folded in here so they ride the same suppression, LSP
        // replay, and MCP projection as every other warning.
        all.addAll(no.sikt.graphitron.rewrite.session.SessionStateWarnings.forConfig(ctx.sessionStateConfig()));
        // The dependency-currency nudge, derived from the resolved graphql-java / jOOQ versions the
        // mojo decoded off both dependency graphs. Same channel and same reason: a whole-build fact
        // with no SDL coordinate, suppressible by rule id like every other finding.
        all.addAll(no.sikt.graphitron.rewrite.dependency.DependencyVersionWarnings.forVersions(ctx.dependencyVersions()));
        // Disabled-rule filter over the *combined* list: keying on the typed rule id after the
        // classifier advisories (schema.warnings()) and engine findings are concatenated means it
        // covers both channels, so a classifier advisory is suppressible by rule id like any
        // other. excludedTypes, in contrast, is applied inside the engine above and reaches only
        // the AST walk; a classifier advisory on an excluded type still fires.
        if (!lintConfig.disabledRuleIds().isEmpty()) {
            all.removeIf(w -> w instanceof BuildWarning.LintFinding lf
                && lintConfig.disabledRuleIds().contains(lf.rule().id()));
        }
        return all;
    }

    private static void logWarnings(List<BuildWarning> warnings) {
        warnings.forEach(w -> {
            var loc = w.location();
            if (loc != null) {
                LOGGER.warn("{}:{}:{}: warning: {}", relativiseSourceName(loc.getSourceName()), loc.getLine(), loc.getColumn(), w.message());
            } else {
                LOGGER.warn("warning: {}", w.message());
            }
        });
    }

    /**
     * The clang-style {@code file:line:col} emission the one-shot build goals keep. Called by the
     * entry points that want it rather than from inside the pipeline: the dev loop renders
     * {@code WatchErrorFormatter}'s grouped tree from the same list instead, and printing both is
     * what made a dev save with errors report itself twice.
     */
    private static void logErrors(List<ValidationError> errors) {
        errors.forEach(e -> {
            var loc = e.location();
            String label = e.kind().messageLabel();
            if (loc != null) {
                LOGGER.error("{}:{}:{}: {}: {}", relativiseSourceName(loc.getSourceName()), loc.getLine(), loc.getColumn(), label, e.message());
            } else {
                LOGGER.error("{}: {}", label, e.message());
            }
        });
    }

    /**
     * Relativise an SDL source path against the user's invocation directory so build logs show
     * 'opptak-subgraph/src/main/resources/schema/foo.graphqls' (when {@code mvn} is run from the
     * multi-module root) or 'src/main/resources/schema/foo.graphqls' (when run from the module
     * itself). Anchored on {@code user.dir} rather than the per-module {@code basedir} so the
     * printed path is always navigable from the shell that produced it; the per-module basedir
     * gives the wrong answer for reactor builds invoked from the parent. Falls back to the
     * original string when the source is null, not absolute, or sits outside the working
     * directory (where relativising would yield a hard-to-read '../...' path).
     */
    private static String relativiseSourceName(String sourceName) {
        if (sourceName == null) return null;
        Path src;
        try {
            src = Path.of(sourceName);
        } catch (java.nio.file.InvalidPathException ex) {
            return sourceName;
        }
        if (!src.isAbsolute()) return sourceName;
        Path base = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path abs = src.normalize();
        if (!abs.startsWith(base)) return sourceName;
        return base.relativize(abs).toString();
    }
}
