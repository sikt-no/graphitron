package no.sikt.graphitron.generate;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.generators.conditions.FieldArgConditionClassGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static no.sikt.graphql.schema.SchemaReadingHelper.getTypeDefinitionRegistry;

/**
 * Entry point for the rewrite code-generation pipeline.
 *
 * <p>This pipeline is independent of the legacy {@link GraphQLGenerator}: it parses the GraphQL
 * schema with its own {@link GraphitronSchemaBuilder}, runs its own list of generators, and
 * writes output to the same configured output directory. Generators added here incrementally
 * replace their legacy counterparts as the rewrite pipeline matures.
 *
 * <p>All generators run in parallel; there is no "wiring last" constraint in this pipeline.
 */
public class GraphQLRewriteGenerator {
    static final Logger LOGGER = LoggerFactory.getLogger(GraphQLRewriteGenerator.class);

    public static void generate() {
        var registry = getTypeDefinitionRegistry(GeneratorConfig.generatorSchemaFiles());
        var schema = GraphitronSchemaBuilder.build(registry);
        var generators = getGenerators(schema);
        generators.parallelStream().forEach(g -> {
            g.generateAllToDirectory(GeneratorConfig.outputDirectory(), GeneratorConfig.outputPackage());
            LOGGER.info("Rewrite: generated sources to: {}.{}", GeneratorConfig.outputPackage(), g.getDefaultSaveDirectoryName());
        });
    }

    static List<ClassGenerator> getGenerators(no.sikt.graphitron.rewrite.GraphitronSchema schema) {
        return List.of(new FieldArgConditionClassGenerator(schema));
    }
}
