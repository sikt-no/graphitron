package no.fellesstudentsystem.graphitron.mojo;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.exception.MutationExceptionStrategyConfigurationGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static no.fellesstudentsystem.graphitron.schema.SchemaReader.getTypeDefinitionRegistry;

/**
 * Class for executing the code generation. Defines which generators should run by default.
 * This assumes that generator configuration is set before calling any of these methods.
 */
public class GraphQLGenerator {
    static final Logger LOGGER = LoggerFactory.getLogger(GraphQLGenerator.class);

    /*
    public static void main(String[] args) {
        generate();
    }
    */

    /**
     * Execute the code generation on the default set of generators and logging settings.
     */
    public static void generate() {
        var processedSchema = getProcessedSchema();
        processedSchema.validate();

        var generators = List.of(
                new FetchDBClassGenerator(processedSchema),
                new FetchResolverClassGenerator(processedSchema),
                new UpdateResolverClassGenerator(processedSchema),
                new UpdateDBClassGenerator(processedSchema),
                new MutationExceptionStrategyConfigurationGenerator(processedSchema)
        );

        generate(generators);
    }

    /**
     * Run a list of generators.
     * @param generators The generators that should be executed.
     */
    public static void generate(List<ClassGenerator<? extends GenerationTarget>> generators) {
        for (var g : generators) {
            g.generateQualifyingObjectsToDirectory(GeneratorConfig.outputDirectory(), GeneratorConfig.outputPackage());
            LOGGER.info("Generated sources to: " + GeneratorConfig.outputPackage() + "." + g.getDefaultSaveDirectoryName());
        }
    }

    /**
     * @return A Graphitron-interpreted version of the schema files set in {@link GeneratorConfig}.
     */
    public static ProcessedSchema getProcessedSchema() {
        // GeneratorConfig.loadProperties();
        var schemaLocations = GeneratorConfig.schemaFiles();
        LOGGER.info("Reading graphql schemas {}", schemaLocations);
        return new ProcessedSchema(getTypeDefinitionRegistry(schemaLocations));
    }
}
