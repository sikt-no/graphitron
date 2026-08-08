package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.command.GlobalCommand;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.rewrite.compile.CompileDependencyGraph;
import no.sikt.graphitron.rewrite.compile.PlanCompileGraph;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.lint.LintEngine;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.federation.KeyNodeSynthesiser;
import no.sikt.graphitron.rewrite.schema.input.DescriptionNoteApplier;
import no.sikt.graphitron.rewrite.schema.input.FederationLinkApplier;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Entry point for the rewrite code-generation pipeline.
 *
 * <p>Parses the GraphQL schema with {@link GraphitronSchemaBuilder}, runs its list of
 * generators, and writes output to the configured output directory.
 */
public class GraphQLRewriteGenerator {
    static final Logger LOGGER = LoggerFactory.getLogger(GraphQLRewriteGenerator.class);

    private static final List<String> OWNED_SUBPACKAGES =
        List.of("", "util", "schema", "types", "conditions", "fetchers", "inputs");

    private final RewriteContext ctx;

    /**
     * Constructs a generator driven by the supplied {@link RewriteContext}. The context's
     * {@code schemaInputs} drive schema loading; {@link TagApplier} and
     * {@link DescriptionNoteApplier} run between parse and classification.
     */
    public GraphQLRewriteGenerator(RewriteContext ctx) {
        this.ctx = ctx;
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
        return runPipeline(loadAttributedRegistry(), false).result();
    }

    /**
     * The dev-loop variant of {@link #generate()}: emits every source and additionally builds the
     * {@link CompileDependencyGraph} the incremental compile driver needs to compute the per-save
     * recompile set. Production one-shot generation ({@code GenerateMojo}) stays on {@link #generate()}
     * and never pays the graph-build cost; only {@code graphitron:dev} (with compilation enabled) reaches
     * for this. The graph is projected from the same {@link EmitPlan} this run rendered from, so it is
     * always consistent with the sources just written.
     */
    public IncrementalGeneration generateIncremental() {
        return runPipeline(loadAttributedRegistry(), true);
    }

    /**
     * A {@link #generateIncremental()} run's products: the {@link GenerationResult} (emitted set + writer
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
     * Triple the LSP needs on every successful regenerate: the
     * {@link CompletionData} catalog (jOOQ + classpath references + scalars),
     * the {@link LspSchemaSnapshot.Built.Current} projection of the parsed
     * user schema's directive surface, and the {@link ValidationReport}
     * carrying every {@link ValidationError} and {@link BuildWarning} the
     * validator produces on the same {@code bundle.model()}. Same parse,
     * three projections; the dev goal swaps all of them atomically through
     * {@code Workspace.setBuildOutput}.
     *
     * <p>The validator runs but never throws on its output: a half-edited
     * buffer with validation errors should still expose tables and scalars
     * so the editor can autocomplete its way out of the typo. The build-time
     * pipeline ({@link #validate()}, {@link #generate()}) is the surface
     * that fails the build on validator errors; this method packages them
     * for the LSP instead.
     */
    public BuildOutput buildOutput() {
        var attributed = loadAttributedRegistry();
        var bundle = GraphitronSchemaBuilder.buildBundle(attributed, ctx);
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var catalog = CatalogBuilder.build(jooq, bundle.assembled(), ctx);
        var snapshot = CatalogBuilder.buildSnapshot(attributed.registry(), bundle.model(), catalog);
        var catalogFacts = CatalogBuilder.buildCatalogFacts(jooq);
        FactCapture.run(attributed.preSynthesisRegistry(), jooq, catalog.externalReferences(),
            new NodeDeclaration(jooq));
        var errors = new GraphitronSchemaValidator().validate(bundle.model());
        var warnings = withLintFindings(bundle.model(), attributed);
        var report = ValidationReport.from(errors, warnings);
        return new BuildOutput(new BuildArtifacts(catalog, snapshot, catalogFacts), report);
    }

    /**
     * Splits the build output along the two lifecycle steps {@link #buildOutput()} spans:
     * classification produces {@link BuildArtifacts} (catalog + snapshot); the validator
     * pass over the same classified model produces {@link ValidationReport}.
     */
    public record BuildOutput(BuildArtifacts artifacts, ValidationReport report) {}

    /**
     * Classification-stage products: the LSP {@link CompletionData} catalog, the
     * directive-projection snapshot, and the {@link CatalogFacts} catalog-discovery projection the
     * MCP {@code catalog.*} tools read. All three are build-derived in one pass and swapped onto
     * the live {@code Workspace} together.
     */
    public record BuildArtifacts(
        CompletionData catalog,
        LspSchemaSnapshot.Built.Current snapshot,
        CatalogFacts catalogFacts
    ) {
        /**
         * Convenience for callers that do not populate the {@link CatalogFacts} projection
         * (LSP / maven dev-loop tests, the catalog-refresh path that reuses a prior catalog);
         * defaults it to {@link CatalogFacts#empty()}.
         */
        public BuildArtifacts(CompletionData catalog, LspSchemaSnapshot.Built.Current snapshot) {
            this(catalog, snapshot, CatalogFacts.empty());
        }
    }

    /**
     * Runs schema loading, attribution, classification, and validation without writing any output.
     * Throws {@link ValidationFailedException} if validation errors are found.
     */
    public void validate() {
        var attributed = loadAttributedRegistry();
        var bundle = GraphitronSchemaBuilder.buildBundle(attributed, ctx);
        var schema = bundle.model();
        logWarnings(withLintFindings(schema, attributed));
        var errors = validateAndLogErrors(schema);
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
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
        var bySource = SchemaInputAttribution.build(ctx.schemaInputs());
        var registry = RewriteSchemaLoader.load(bySource.keySet());
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
            KeyNodeSynthesiser.apply(registry,
                new NodeDeclaration(new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader())));
        }
        return new AttributedRegistry(registry, preSynthesis, injectedNames);
    }

    /**
     * Runs the capture loads into a fact store for this pass and discards it. The store shadows
     * the live pipeline: nothing reads it, so a capture that recorded the wrong thing cannot
     * change what the build accepts, rejects, emits, or reports. Consumers migrate onto it one at
     * a time, and until the first one does, the agreement tests are what keep the shadow honest.
     *
     * <p>Both loads read exactly what the pipeline beside them reads: the parsed registry (before
     * the synthesis rewrites, which is what {@link AttributedRegistry#preSynthesisRegistry()}
     * hands back), the jOOQ catalog projection, and the classpath scan.
     */
    private void captureFacts(AttributedRegistry attributed) {
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        FactCapture.run(attributed.preSynthesisRegistry(),
            jooq,
            CatalogBuilder.buildExternalReferences(ctx),
            new NodeDeclaration(jooq));
    }

    private IncrementalGeneration runPipeline(AttributedRegistry attributed, boolean buildCompileGraph) {
        var bundle = GraphitronSchemaBuilder.buildBundle(attributed, ctx);
        var schema = bundle.model();
        var assembled = bundle.assembled();
        boolean federationLink = bundle.federationLink();

        logWarnings(withLintFindings(schema, attributed));

        var errors = validateAndLogErrors(schema);
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }

        captureFacts(attributed);

        String outputPackage = ctx.outputPackage();

        // The plan is produced before the per-type generators run: the launcher relation's rows
        // are read by the fetcher generator (a root coordinate with a row gets the launcher
        // emission, one without falls through to its legacy builder).
        var plan = EmitPlan.produce(schema, federationLink, bundle.usesOneOf(), ctx.sessionStateConfig(), outputPackage);

        var fetcherClasses = TypeFetcherGenerator.generate(schema, assembled, outputPackage,
            plan.launchers(), plan.typeUnits().fetchers());
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
            no.sikt.graphitron.render.ConditionGlueRenderer.render(plan.conditions().rows(), outputPackage),
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
        CompileDependencyGraph graph = buildCompileGraph
            ? PlanCompileGraph.fromPlan(plan, schema)
            : null;
        return new IncrementalGeneration(result, graph);
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
            case CONNECTION_RUNTIME -> ConnectionRuntimeClassGenerator.generate(outputPackage, ctx.sessionStateConfig(), tenantKeyType);
            case TRANSACTION_PROVIDER -> GraphitronTransactionProviderGenerator.generate(outputPackage);
            case CONNECTION_INSTRUMENTATION -> GraphitronConnectionInstrumentationGenerator.generate(outputPackage, tenantKeyType != null);
            case CONSTRAINT_VIOLATIONS -> ConstraintViolationsClassGenerator.generate();
            case CLIENT_EXCEPTION -> GraphitronClientExceptionClassGenerator.generate();
            case ERROR_ROUTER -> ErrorRouterClassGenerator.generate(outputPackage);
            case OUTCOME -> OutcomeClassGenerator.generate(outputPackage);
            case ERROR_MAPPINGS -> ErrorMappingsClassGenerator.generate(schema, outputPackage);
            case SCHEMA_CLASS -> GraphitronSchemaClassGenerator.generate(schema, assembled, schemaShapeRows, outputPackage, federationLink);
            case QUERY_NODE_FETCHER -> QueryNodeFetcherClassGenerator.generate(schema, outputPackage);
            case FACADE -> GraphitronFacadeGenerator.generate(schema, outputPackage, federationLink);
            case DEV_EXECUTOR -> GraphitronDevExecutorGenerator.generate(schema, outputPackage, ctx.sessionStateConfig(), federationLink);
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
        // from the <sessionState> config and whether the schema uses @service. Folded in here so they ride
        // the same suppression, LSP replay, and MCP projection as every other warning.
        boolean hasService = schema.fields().values().stream()
            .anyMatch(f -> f instanceof no.sikt.graphitron.rewrite.model.ServiceField);
        all.addAll(no.sikt.graphitron.rewrite.session.SessionStateWarnings.forConfig(ctx.sessionStateConfig(), hasService));
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

    private static List<ValidationError> validateAndLogErrors(GraphitronSchema schema) {
        var errors = new GraphitronSchemaValidator().validate(schema);
        errors.forEach(e -> {
            var loc = e.location();
            String label = e.kind().messageLabel();
            if (loc != null) {
                LOGGER.error("{}:{}:{}: {}: {}", relativiseSourceName(loc.getSourceName()), loc.getLine(), loc.getColumn(), label, e.message());
            } else {
                LOGGER.error("{}: {}", label, e.message());
            }
        });
        return errors;
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
