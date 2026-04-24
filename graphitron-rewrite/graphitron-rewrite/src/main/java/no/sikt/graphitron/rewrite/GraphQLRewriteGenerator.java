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
     * Runs the full code-generation pipeline: loads and attributes schema inputs, classifies,
     * validates, and writes all generated sources to the configured output directory.
     */
    public void generate() {
        runPipeline(loadAttributedRegistry());
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

        String outputPackage = ctx.outputPackage();
        String jooqPackage   = ctx.jooqPackage();

        var fetcherClasses = TypeFetcherGenerator.generate(schema, outputPackage, jooqPackage);
        var fetcherBodies  = FetcherRegistrationsEmitter.emit(schema, outputPackage, jooqPackage);

        write(GraphitronValuesClassGenerator.generate(),                                          "util");
        write(ColumnFetcherClassGenerator.generate(),                                             "util");
        write(NodeIdEncoderClassGenerator.generate(),                                             "util");
        write(ConnectionResultClassGenerator.generate(outputPackage),                             "util");
        write(ConnectionHelperClassGenerator.generate(outputPackage),                             "util");
        write(OrderByResultClassGenerator.generate(),                                             "util");
        write(GraphitronContextInterfaceGenerator.generate(),                                     "schema");
        write(EnumTypeGenerator.generate(assembled),                                              "schema");
        write(InputTypeGenerator.generate(assembled),                                             "schema");
        write(ObjectTypeGenerator.generate(assembled, fetcherBodies),                             "schema");
        write(GraphitronSchemaClassGenerator.generate(assembled, fetcherBodies.keySet(), outputPackage), "schema");
        write(GraphitronFacadeGenerator.generate(outputPackage),                                  "");
        write(TypeClassGenerator.generate(schema, outputPackage, jooqPackage),                   "types");
        write(TypeConditionsGenerator.generate(schema, jooqPackage),                              "conditions");
        write(QueryConditionsGenerator.generate(schema, outputPackage, jooqPackage),             "conditions");
        write(fetcherClasses,                                                                      "fetchers");
    }

    private void write(List<TypeSpec> specs, String subPackage) {
        String outputPackage = ctx.outputPackage();
        var packageName = subPackage.isEmpty()
            ? outputPackage
            : outputPackage + "." + subPackage;
        specs.forEach(spec -> {
            try {
                JavaFile.builder(packageName, spec).indent("    ").build()
                    .writeTo(ctx.outputDirectory().toFile());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        LOGGER.info("Rewrite: generated sources to: {}", packageName);
    }
}
