package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.generators.QueryConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
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
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator;
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
import java.util.LinkedHashSet;
import java.util.List;
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
     */
    public void generate() {
        runPipeline(loadAttributedRegistry());
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
        var errors = new GraphitronSchemaValidator().validate(bundle.model());
        var warnings = bundle.model().warnings();
        var report = ValidationReport.from(errors, warnings);
        return new BuildOutput(new BuildArtifacts(catalog, snapshot), report);
    }

    /**
     * Splits the build output along the two lifecycle steps {@link #buildOutput()} spans:
     * classification produces {@link BuildArtifacts} (catalog + snapshot); the validator
     * pass over the same classified model produces {@link ValidationReport}.
     */
    public record BuildOutput(BuildArtifacts artifacts, ValidationReport report) {}

    /**
     * Classification-stage products: catalog plus directive-projection snapshot.
     */
    public record BuildArtifacts(CompletionData catalog, LspSchemaSnapshot.Built.Current snapshot) {}

    /**
     * Runs schema loading, attribution, classification, and validation without writing any output.
     * Throws {@link ValidationFailedException} if validation errors are found.
     */
    public void validate() {
        var bundle = GraphitronSchemaBuilder.buildBundle(loadAttributedRegistry(), ctx);
        var schema = bundle.model();
        logWarnings(schema);
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
     * {@link graphql.schema.idl.TypeDefinitionRegistry} and the
     * {@code federationLink} flag captured from
     * {@link FederationLinkApplier#apply}'s return value, so downstream stages
     * read the flag without re-walking the registry.
     */
    AttributedRegistry loadAttributedRegistry() {
        var bySource = SchemaInputAttribution.build(ctx.schemaInputs());
        var registry = RewriteSchemaLoader.load(bySource.keySet());
        TagLinkSynthesiser.apply(registry, bySource);
        boolean federationLink = FederationLinkApplier.apply(registry);
        if (federationLink) {
            KeyNodeSynthesiser.apply(registry);
        }
        TagApplier.apply(registry, bySource);
        DescriptionNoteApplier.apply(registry, bySource);
        return new AttributedRegistry(registry, federationLink);
    }

    private void runPipeline(AttributedRegistry attributed) {
        var bundle = GraphitronSchemaBuilder.buildBundle(attributed, ctx);
        var schema = bundle.model();
        var assembled = bundle.assembled();
        boolean federationLink = bundle.federationLink();

        logWarnings(schema);

        var errors = validateAndLogErrors(schema);
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }

        String outputPackage = ctx.outputPackage();

        var fetcherClasses = TypeFetcherGenerator.generate(schema, assembled, outputPackage);
        var fetcherBodies  = FetcherRegistrationsEmitter.emit(schema, outputPackage);

        Set<Path> emittedThisRun = new LinkedHashSet<>();
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
        write(ConstraintViolationsClassGenerator.generate(),                                      "schema",     emittedThisRun);
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
        write(QueryNodeFetcherClassGenerator.generate(schema, outputPackage),                      "fetchers",   emittedThisRun);
        emittedThisRun.add(SchemaSdlEmitter.emit(assembled, schema, federationLink, ctx.outputResourcesDirectory(), outputPackage));
        sweepOrphans(emittedThisRun);
    }

    private void write(List<TypeSpec> specs, String subPackage, Set<Path> emittedThisRun) {
        String outputPackage = ctx.outputPackage();
        var packageName = subPackage.isEmpty()
            ? outputPackage
            : outputPackage + "." + subPackage;
        for (TypeSpec spec : specs) {
            try {
                emittedThisRun.add(
                    JavaFile.builder(packageName, spec).indent("    ").build()
                        .writeToPath(ctx.outputDirectory(), StandardCharsets.UTF_8)
                );
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

    private static void logWarnings(GraphitronSchema schema) {
        schema.warnings().forEach(w -> {
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
