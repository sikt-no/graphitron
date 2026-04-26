package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.generators.QueryConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.DescriptionNoteApplier;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.rewrite.schema.input.TagApplier;
import no.sikt.graphitron.rewrite.generators.schema.EnumTypeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronFacadeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronSchemaClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.InputTypeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ObjectTypeGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronContextInterfaceGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.QueryNodeFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.OrderByResultClassGenerator;
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
        List.of("", "util", "schema", "types", "conditions", "fetchers");

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
     * Loads the schema and assembles a {@link CompletionData} snapshot for
     * the LSP. Skips validation deliberately: a half-edited buffer with
     * validation errors should still expose tables and scalars so the
     * editor can autocomplete its way out of the typo.
     *
     * <p>The dev goal calls this once at startup and on every classpath
     * watcher trigger; the result swaps into {@code Workspace} atomically.
     */
    public CompletionData buildCatalog() {
        var bundle = GraphitronSchemaBuilder.buildBundle(loadAttributedRegistry(), ctx);
        var jooq = new JooqCatalog(ctx.jooqPackage());
        return CatalogBuilder.build(jooq, bundle.assembled());
    }

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
     */
    graphql.schema.idl.TypeDefinitionRegistry loadAttributedRegistry() {
        var bySource = SchemaInputAttribution.build(ctx.schemaInputs());
        var registry = RewriteSchemaLoader.load(bySource.keySet());
        TagApplier.apply(registry, bySource);
        DescriptionNoteApplier.apply(registry, bySource);
        return registry;
    }

    private void runPipeline(graphql.schema.idl.TypeDefinitionRegistry registry) {
        var bundle = GraphitronSchemaBuilder.buildBundle(registry, ctx);
        var schema = bundle.model();
        var assembled = bundle.assembled();

        logWarnings(schema);

        var errors = validateAndLogErrors(schema);
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }

        String outputPackage = ctx.outputPackage();
        String jooqPackage   = ctx.jooqPackage();

        var fetcherClasses = TypeFetcherGenerator.generate(schema, outputPackage, jooqPackage);
        var fetcherBodies  = FetcherRegistrationsEmitter.emit(schema, outputPackage, jooqPackage);

        Set<Path> emittedThisRun = new LinkedHashSet<>();
        write(GraphitronValuesClassGenerator.generate(),                                          "util",       emittedThisRun);
        write(ColumnFetcherClassGenerator.generate(),                                             "util",       emittedThisRun);
        write(NodeIdEncoderClassGenerator.generate(),                                             "util",       emittedThisRun);
        write(ConnectionResultClassGenerator.generate(outputPackage),                             "util",       emittedThisRun);
        write(ConnectionHelperClassGenerator.generate(outputPackage),                             "util",       emittedThisRun);
        write(OrderByResultClassGenerator.generate(),                                             "util",       emittedThisRun);
        write(GraphitronContextInterfaceGenerator.generate(),                                     "schema",     emittedThisRun);
        write(EnumTypeGenerator.generate(schema),                                                 "schema",     emittedThisRun);
        write(InputTypeGenerator.generate(schema, assembled),                                     "schema",     emittedThisRun);
        write(ObjectTypeGenerator.generate(schema, assembled, fetcherBodies),                     "schema",     emittedThisRun);
        write(GraphitronSchemaClassGenerator.generate(schema, assembled, fetcherBodies.keySet(), outputPackage), "schema", emittedThisRun);
        write(GraphitronFacadeGenerator.generate(outputPackage),                                  "",           emittedThisRun);
        write(TypeClassGenerator.generate(schema, outputPackage, jooqPackage),                   "types",      emittedThisRun);
        write(TypeConditionsGenerator.generate(schema, jooqPackage),                              "conditions", emittedThisRun);
        write(QueryConditionsGenerator.generate(schema, outputPackage, jooqPackage),             "conditions", emittedThisRun);
        write(fetcherClasses,                                                                      "fetchers",   emittedThisRun);
        write(QueryNodeFetcherClassGenerator.generate(schema, outputPackage, jooqPackage),         "fetchers",   emittedThisRun);
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
        LOGGER.info("Rewrite: generated sources to: {}", packageName);
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
                LOGGER.warn("{}:{}:{}: warning: {}", loc.getSourceName(), loc.getLine(), loc.getColumn(), w.message());
            } else {
                LOGGER.warn("warning: {}", w.message());
            }
        });
    }

    private static List<ValidationError> validateAndLogErrors(GraphitronSchema schema) {
        var errors = new GraphitronSchemaValidator().validate(schema);
        errors.forEach(e -> {
            var loc = e.location();
            String kindPrefix = "[" + e.kind().displayName() + "] ";
            if (loc != null) {
                LOGGER.error("{}:{}:{}: error: {}{}", loc.getSourceName(), loc.getLine(), loc.getColumn(), kindPrefix, e.message());
            } else {
                LOGGER.error("error: {}{}", kindPrefix, e.message());
            }
        });
        return errors;
    }
}
