package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.QueryConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
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

    public static void generate() {
        var registry = RewriteSchemaLoader.load(RewriteConfig.generatorSchemaFiles());
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
