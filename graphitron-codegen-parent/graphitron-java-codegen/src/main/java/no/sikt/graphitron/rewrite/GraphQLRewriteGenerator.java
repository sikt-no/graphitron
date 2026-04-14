package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.GraphitronWiringClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFieldsGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronValuesClassGenerator;
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
        var registry = getTypeDefinitionRegistry(GeneratorConfig.generatorSchemaFiles());
        var schema = GraphitronSchemaBuilder.build(registry);

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

        var fieldsClasses = TypeFieldsGenerator.generate(schema);
        var fieldsClassNames = fieldsClasses.stream().map(TypeSpec::name).toList();

        write(GraphitronValuesClassGenerator.generate(),          "rewrite");
        write(TypeClassGenerator.generate(schema),                "rewrite.types");
        write(TypeConditionsGenerator.generate(schema),           "rewrite.types");
        write(fieldsClasses,                                      "rewrite.types");
        write(List.of(GraphitronWiringClassGenerator.generate(fieldsClassNames)), "rewrite");
    }

    private static void write(List<TypeSpec> specs, String subPackage) {
        var packageName = GeneratorConfig.outputPackage() + "." + subPackage;
        specs.forEach(spec -> {
            try {
                JavaFile.builder(packageName, spec).indent("    ").build()
                    .writeTo(new File(GeneratorConfig.outputDirectory()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        LOGGER.info("Rewrite: generated sources to: {}", packageName);
    }
}
