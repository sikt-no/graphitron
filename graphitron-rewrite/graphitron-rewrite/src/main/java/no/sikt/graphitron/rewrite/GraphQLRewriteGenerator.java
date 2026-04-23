package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.GraphitronWiringClassGenerator;
import no.sikt.graphitron.rewrite.generators.QueryConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.generators.WiringClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.EnumTypeGenerator;
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

import static no.sikt.graphql.schema.SchemaReadingHelper.getTypeDefinitionRegistry;

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
        var registry = getTypeDefinitionRegistry(RewriteConfig.generatorSchemaFiles());
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
        var wiringClasses  = WiringClassGenerator.generate(schema);
        var aggregator     = GraphitronWiringClassGenerator.generate(
            wiringClasses.stream().map(TypeSpec::name).toList());
        var typesWithWiring = wiringClasses.stream()
            .map(t -> t.name().replaceFirst("Wiring$", ""))
            .collect(java.util.stream.Collectors.toSet());

        write(GraphitronValuesClassGenerator.generate(),          "rewrite");
        write(ColumnFetcherClassGenerator.generate(),             "rewrite");
        write(NodeIdEncoderClassGenerator.generate(),             "rewrite");
        write(ConnectionResultClassGenerator.generate(),          "rewrite");
        write(ConnectionHelperClassGenerator.generate(),          "rewrite");
        write(OrderByResultClassGenerator.generate(),             "rewrite");
        write(GraphitronContextInterfaceGenerator.generate(),     "rewrite.schema");
        write(EnumTypeGenerator.generate(assembled),              "rewrite.schema");
        write(InputTypeGenerator.generate(assembled),             "rewrite.schema");
        write(ObjectTypeGenerator.generate(assembled, typesWithWiring),            "rewrite.schema");
        write(GraphitronSchemaClassGenerator.generate(assembled, typesWithWiring), "rewrite.schema");
        write(GraphitronFacadeGenerator.generate(),               "rewrite.schema");
        write(TypeClassGenerator.generate(schema),                "rewrite.types");
        write(TypeConditionsGenerator.generate(schema),           "rewrite.conditions");
        write(QueryConditionsGenerator.generate(schema),          "rewrite.conditions");
        write(fetcherClasses,                                      "rewrite.fetchers");
        write(wiringClasses,                                       "rewrite.wiring");
        write(List.of(aggregator),                                 "rewrite");
    }

    private static void write(List<TypeSpec> specs, String subPackage) {
        var packageName = RewriteConfig.outputPackage() + "." + subPackage;
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
