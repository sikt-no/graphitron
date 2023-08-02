package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static no.fellesstudentsystem.graphitron.schema.SchemaReader.getTypeDefinitionRegistry;

public class GraphQLGenerator {
    static final Logger LOGGER = LoggerFactory.getLogger(GraphQLGenerator.class);

    /*
    public static void main(String[] args) {
        generate();
    }
    */

    public static void generate() {
        generate(true);
    }

    public static void generate(boolean warnDirectives) {
        var processedSchema = getProcessedSchema(warnDirectives);
        processedSchema.validate();

        var generators = List.of(
                new FetchDBClassGenerator(processedSchema),
                new FetchResolverClassGenerator(processedSchema),
                new UpdateResolverClassGenerator(processedSchema),
                new UpdateDBClassGenerator(processedSchema)
        );

        generate(generators);
    }

    public static void generate(List<ClassGenerator<? extends GenerationTarget>> generators) {
        for (var g : generators) {
            g.generateQualifyingObjectsToDirectory(GeneratorConfig.outputDirectory(), GeneratorConfig.outputPackage());
            LOGGER.info("Generated sources to: " + GeneratorConfig.outputPackage() + "." + g.getDefaultSaveDirectoryName());
        }
    }

    public static ProcessedSchema getProcessedSchema(boolean warnDirectives) {
        // GeneratorConfig.loadProperties();
        var schemaLocations = GeneratorConfig.schemaFiles();
        LOGGER.info("Reading graphql schemas {}", schemaLocations);
        return new ProcessedSchema(getTypeDefinitionRegistry(schemaLocations), warnDirectives);
    }
}
