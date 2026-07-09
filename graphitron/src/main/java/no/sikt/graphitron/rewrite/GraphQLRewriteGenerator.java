package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.compile.CompileDependencyGraph;
import no.sikt.graphitron.rewrite.compile.CompileDependencyGraphBuilder;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.generators.QueryConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator;
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
import no.sikt.graphitron.rewrite.generators.schema.GraphitronFacadeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronSchemaClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.InputRecordGenerator;
import no.sikt.graphitron.rewrite.generators.schema.InputTypeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ObjectTypeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.OneOfDirectiveSdl;
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
 * <p>This pipeline is independent of the legacy {@link no.sikt.graphitron.generate.GraphQLGenerator}: it parses the GraphQL
 * schema with its own {@link GraphitronSchemaBuilder}, runs its own list of generators, and
 * writes output to the same configured output directory. Generators added here incrementally
 * replace their legacy counterparts as the rewrite pipeline matures.
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
     * whose on-disk content actually changed. The idempotent writer already computes that delta per
     * file (it writes only on a content mismatch); surfacing it here is what lets the incremental
     * compile engine (R410) recompile the changed sub-closure instead of the whole tree. Callers that
     * only need the write-to-disk side effect may ignore the return value.
     */
    public GenerationResult generate() {
        return runPipeline(loadAttributedRegistry(), false).result();
    }

    /**
     * The dev-loop variant of {@link #generate()}: emits every source and additionally builds the
     * {@link CompileDependencyGraph} the R410 incremental compile driver needs to compute the per-save
     * recompile set. Production one-shot generation ({@code GenerateMojo}) stays on {@link #generate()}
     * and never pays the graph-build cost; only {@code graphitron:dev} (with compilation enabled) reaches
     * for this. The graph is built from the same classified model this run rendered, so it is always
     * consistent with the sources just written.
     */
    public IncrementalGeneration generateIncremental() {
        return runPipeline(loadAttributedRegistry(), true);
    }

    /**
     * A {@link #generateIncremental()} run's products: the {@link GenerationResult} (emitted set + writer
     * delta + emitted {@link TypeSpec}s) paired with the {@link CompileDependencyGraph} coarsened from
     * the same classified model. Together these are the raw material the R410 dev-loop compile driver
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
     * {@link TypeSpec} per unit. This is the raw material R410's incremental compile driver reads: the
     * FQCN keys are the graph's node identities (and the live set for the {@code .class} orphan sweep),
     * the {@link TypeSpec} values feed {@code AbiSignature.hash} for ABI-vs-body discrimination, and
     * {@code changedUnits} is the writer's delta by FQCN. Hashing is left to the consumer, so a
     * production {@code generate()} that ignores the result pays no ABI-hashing cost; retaining the
     * specs is a transient reference the run discards. The SDL resource is not a compilation unit and
     * appears in neither map.
     */
    public record GenerationResult(Set<Path> emitted, Set<Path> changed,
                                   Map<String, TypeSpec> emittedUnits, Set<String> changedUnits) {}

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

        /** Records a compilation-unit write, folding its {@code changed} flag into both deltas. */
        void record(String fqcn, TypeSpec spec, JavaFile.WriteResult result) {
            emitted.add(result.path());
            emittedUnits.put(fqcn, spec);
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
     * Classification-stage products: the LSP {@link CompletionData} catalog, the directive-projection
     * snapshot, and (R362) the {@link CatalogFacts} catalog-discovery projection the MCP
     * {@code catalog.*} tools read. All three are build-derived in one pass and swapped onto the
     * live {@code Workspace} together.
     */
    public record BuildArtifacts(
        CompletionData catalog,
        LspSchemaSnapshot.Built.Current snapshot,
        CatalogFacts catalogFacts
    ) {
        /**
         * Convenience for callers that do not populate the R362 {@link CatalogFacts} projection
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
        if (!injectedNames.isEmpty()) {
            KeyNodeSynthesiser.apply(registry);
        }
        TagApplier.apply(registry, bySource);
        DescriptionNoteApplier.apply(registry, bySource);
        return new AttributedRegistry(registry, injectedNames);
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

        String outputPackage = ctx.outputPackage();

        var fetcherClasses = TypeFetcherGenerator.generate(schema, assembled, outputPackage);
        var fetcherBodies  = FetcherRegistrationsEmitter.emit(schema, outputPackage);

        EmissionLog emittedThisRun = new EmissionLog();
        write(GraphitronValuesClassGenerator.generate(),                                          "util",       emittedThisRun);
        write(LightFetcherClassGenerator.generate(outputPackage),                                 "util",       emittedThisRun);
        write(NodeIdEncoderClassGenerator.generate(schema),                                       "util",       emittedThisRun);
        write(EntityFetcherDispatchClassGenerator.generate(schema, outputPackage),                "util",       emittedThisRun);
        write(ConnectionResultClassGenerator.generate(outputPackage),                             "util",       emittedThisRun);
        write(ConnectionHelperClassGenerator.generate(outputPackage),                             "util",       emittedThisRun);
        // R283: the runtime _Service.sdl helper serves only the federation build arm (the wrapped
        // `return` in GraphitronSchemaClassGenerator's two-arg build, itself inside `if
        // (federationLink)`). A non-federation schema that uses @oneOf has no _Service.sdl to
        // correct (its file arm prints the definition through SchemaPrinter already), so gating on
        // usesOneOf alone would emit a dead, uncalled helper into a non-federation consumer's util.
        if (federationLink && OneOfDirectiveSdl.usesOneOf(assembled)) {
            write(OneOfDirectiveSdlGenerator.generate(outputPackage),                              "util",       emittedThisRun);
        }
        write(PolymorphicSelectionSetClassGenerator.generate(),                                   "util",       emittedThisRun);
        write(OrderByResultClassGenerator.generate(),                                             "util",       emittedThisRun);
        write(GraphitronContextInterfaceGenerator.generate(),                                     "schema",     emittedThisRun);
        write(ConnectionRuntimeClassGenerator.generate(outputPackage),                             "schema",     emittedThisRun);
        write(GraphitronTransactionProviderGenerator.generate(outputPackage),                       "schema",     emittedThisRun);
        write(GraphitronConnectionInstrumentationGenerator.generate(outputPackage),                 "schema",     emittedThisRun);
        write(ConstraintViolationsClassGenerator.generate(),                                      "schema",     emittedThisRun);
        write(GraphitronClientExceptionClassGenerator.generate(),                                 "schema",     emittedThisRun);
        write(ErrorRouterClassGenerator.generate(outputPackage),                                  "schema",     emittedThisRun);
        write(OutcomeClassGenerator.generate(outputPackage),                                      "schema",     emittedThisRun);
        write(ErrorMappingsClassGenerator.generate(schema, outputPackage),                        "schema",     emittedThisRun);
        write(EnumTypeGenerator.generate(schema),                                                 "schema",     emittedThisRun);
        write(InputTypeGenerator.generate(schema),                                                "schema",     emittedThisRun);
        write(InputRecordGenerator.generate(schema, assembled, outputPackage),                    "inputs",     emittedThisRun);
        write(ObjectTypeGenerator.generate(schema, assembled, fetcherBodies),                     "schema",     emittedThisRun);
        write(GraphitronSchemaClassGenerator.generate(schema, assembled, fetcherBodies.keySet(), outputPackage, federationLink), "schema", emittedThisRun);
        write(GraphitronFacadeGenerator.generate(schema, outputPackage, federationLink),          "",           emittedThisRun);
        write(TypeClassGenerator.generate(schema, outputPackage),                                 "types",      emittedThisRun);
        write(TypeConditionsGenerator.generate(schema, outputPackage),                            "conditions", emittedThisRun);
        write(QueryConditionsGenerator.generate(schema, outputPackage),                           "conditions", emittedThisRun);
        write(fetcherClasses,                                                                      "fetchers",   emittedThisRun);
        write(ConnectionFetcherClassGenerator.generate(schema, outputPackage),                     "fetchers",   emittedThisRun);
        write(ErrorTypeFetcherClassGenerator.generate(schema, outputPackage),                      "fetchers",   emittedThisRun);
        write(QueryNodeFetcherClassGenerator.generate(schema, outputPackage),                      "fetchers",   emittedThisRun);
        emittedThisRun.add(SchemaSdlEmitter.emit(assembled, schema, federationLink, ctx.outputResourcesDirectory(), outputPackage));
        sweepOrphans(emittedThisRun.emitted);
        var result = new GenerationResult(
            Collections.unmodifiableSet(emittedThisRun.emitted),
            Collections.unmodifiableSet(emittedThisRun.changed),
            Collections.unmodifiableMap(emittedThisRun.emittedUnits),
            Collections.unmodifiableSet(emittedThisRun.changedUnits)
        );
        // The compile-dependency graph is coarsened from the same classified model, but only the
        // dev-loop incremental compiler needs it; production generate() skips the build (buildCompileGraph
        // is false there). See the sourcing seam in CompileDependencyGraph.
        CompileDependencyGraph graph = buildCompileGraph
            ? CompileDependencyGraphBuilder.fromModel(schema, outputPackage)
            : null;
        return new IncrementalGeneration(result, graph);
    }

    private void write(List<TypeSpec> specs, String subPackage, EmissionLog emittedThisRun) {
        String outputPackage = ctx.outputPackage();
        var packageName = subPackage.isEmpty()
            ? outputPackage
            : outputPackage + "." + subPackage;
        for (TypeSpec spec : specs) {
            try {
                var result = JavaFile.builder(packageName, spec).indent("    ").build()
                    .writeToPathReporting(ctx.outputDirectory(), StandardCharsets.UTF_8);
                emittedThisRun.record(packageName + "." + spec.name(), spec, result);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
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
     * Classification advisories ({@code schema.warnings()}) plus the SDL lint engine's findings over
     * the same parsed registry (R398). Lint findings ride the {@link BuildWarning} channel here at the
     * report-assembly surfaces rather than inside {@link GraphitronSchemaBuilder}, so the per-build
     * classifier model stays advisory-only and only the user-facing report carries the lint surface.
     */
    private List<BuildWarning> withLintFindings(GraphitronSchema schema, AttributedRegistry attributed) {
        LintConfig lintConfig = ctx.lintConfig();
        var all = new java.util.ArrayList<BuildWarning>(schema.warnings());
        // excludedTypes widens the engine's per-type skip; injectedNames (R407) excludes the
        // federation @link injector's generator-owned definitions at the same boundary.
        all.addAll(LintEngine.builtIn(lintConfig.excludedTypePatterns())
            .run(attributed.registry(), attributed.injectedNames()));
        // Disabled-rule filter over the *combined* list: keying on the typed rule id after the
        // classifier advisories (schema.warnings()) and engine findings are concatenated means it
        // covers both channels, so a classifier advisory is suppressible by rule id like any other
        // (R408). excludedTypes, in contrast, is applied inside the engine above and reaches only the
        // AST walk; a classifier advisory on an excluded type still fires.
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
