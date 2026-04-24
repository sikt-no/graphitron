package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
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
import no.sikt.graphitron.rewrite.generators.util.OrderByResultClassGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

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

    private final RewriteContext ctx;

    /**
     * Construct an instance-based generator driven by a {@link RewriteContext}.
     *
     * <p>When instance-mode is used, the context's {@code schemaInputs} drive schema
     * loading, and the {@link TagApplier} / {@link DescriptionNoteApplier} stages run
     * between parse and classification. The static {@link #generate()} entry point
     * stays intact for consumers that still drive rewrite through the legacy Mojo;
     * the Maven-plugin plan retires that call site later.
     */
    public GraphQLRewriteGenerator(RewriteContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Instance entry point. Named {@code run} (not {@code generate}) because Java
     * disallows an instance and a static method sharing the same signature in one
     * class, and the static {@link #generate()} is still live for legacy-Mojo
     * callers. The Maven-plugin plan unifies this onto a single {@code generate}
     * name once the static path retires.
     */
    public void run() {
        var bySource = SchemaInputAttribution.build(ctx.schemaInputs());
        var registry = RewriteSchemaLoader.load(bySource.keySet());
        TagApplier.apply(registry, bySource);
        DescriptionNoteApplier.apply(registry, bySource);
        runPipeline(registry);
    }

    public static void generate() {
        var registry = RewriteSchemaLoader.load(RewriteConfig.generatorSchemaFiles());
        runPipeline(registry);
    }

    private static void runPipeline(graphql.schema.idl.TypeDefinitionRegistry registry) {
        var bundle = GraphitronSchemaBuilder.buildBundle(registry);
        var schema = bundle.model();
        var assembled = bundle.assembled();

        schema.warnings().forEach(w -> {
            var loc = w.location();
            if (loc != null) {
                LOGGER.warn("{}:{}:{}: warning: {}", loc.getSourceName(), loc.getLine(), loc.getColumn(), w.message());
            } else {
                LOGGER.warn("warning: {}", w.message());
            }
        });

        var errors = new GraphitronSchemaValidator().validate(schema);
        if (!errors.isEmpty()) {
            errors.forEach(e -> {
                var loc = e.location();
                if (loc != null) {
                    LOGGER.error("{}:{}:{}: error: {}", loc.getSourceName(), loc.getLine(), loc.getColumn(), e.message());
                } else {
                    LOGGER.error("error: {}", e.message());
                }
            });
            throw new RuntimeException("Rewrite schema validation failed with " + errors.size() + " error(s)");
        }

        var fetcherClasses = TypeFetcherGenerator.generate(schema);
        var fetcherBodies  = FetcherRegistrationsEmitter.emit(schema);

        write(GraphitronValuesClassGenerator.generate(),          "util");
        write(ColumnFetcherClassGenerator.generate(),             "util");
        write(NodeIdEncoderClassGenerator.generate(),             "util");
        write(ConnectionResultClassGenerator.generate(),          "util");
        write(ConnectionHelperClassGenerator.generate(),          "util");
        write(OrderByResultClassGenerator.generate(),             "util");
        write(GraphitronContextInterfaceGenerator.generate(),     "schema");
        write(EnumTypeGenerator.generate(assembled),              "schema");
        write(InputTypeGenerator.generate(assembled),             "schema");
        write(ObjectTypeGenerator.generate(assembled, fetcherBodies),            "schema");
        write(GraphitronSchemaClassGenerator.generate(assembled, fetcherBodies.keySet()), "schema");
        write(GraphitronFacadeGenerator.generate(),               "");
        write(TypeClassGenerator.generate(schema),                "types");
        write(TypeConditionsGenerator.generate(schema),           "conditions");
        write(QueryConditionsGenerator.generate(schema),          "conditions");
        write(fetcherClasses,                                      "fetchers");
    }

    private static void write(List<TypeSpec> specs, String subPackage) {
        var packageName = subPackage.isEmpty()
            ? RewriteConfig.outputPackage()
            : RewriteConfig.outputPackage() + "." + subPackage;
        specs.forEach(spec -> {
            try {
                JavaFile.builder(packageName, spec).indent("    ").build()
                    .writeTo(new File(RewriteConfig.outputDirectory()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        LOGGER.info("Rewrite: generated sources to: {}", packageName);
    }
}
