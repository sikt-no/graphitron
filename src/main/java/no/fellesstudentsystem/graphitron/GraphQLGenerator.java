package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.generators.db.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static no.fellesstudentsystem.graphql.schema.SchemaWriter.assembleSchema;
import static no.fellesstudentsystem.graphql.schema.SchemaReader.getTypeDefinitionRegistry;
import static no.fellesstudentsystem.graphql.schema.SchemaWriter.writeSchemaToDirectory;

public class GraphQLGenerator {
    static final Logger LOGGER = LoggerFactory.getLogger(GraphQLGenerator.class);

    public static void main(String[] args) throws IOException {
        generate();
    }

    public static void generate() throws IOException {
        GeneratorConfig.loadProperties();
        var schemaLocations = new ArrayList<>(GeneratorConfig.schemaLocations());
        LOGGER.info("Reading graphql schemas {}", schemaLocations);
        var typeRegistry = getTypeDefinitionRegistry(schemaLocations);
        var schema = assembleSchema(typeRegistry);
        writeSchemaToDirectory(schema, GeneratorConfig.outputDirectory());
        var processedSchema = new ProcessedSchema(typeRegistry);

        var generators = List.of(
                new FetchDBClassGenerator(processedSchema),
                new FetchResolverClassGenerator(processedSchema),
                new UpdateResolverClassGenerator(processedSchema)
        );

        for (var g : generators) {
            g.generateQualifyingObjectsToDirectory(GeneratorConfig.outputDirectory(), GeneratorConfig.outputPackage());
            LOGGER.info("Generated sources to: " + GeneratorConfig.outputPackage() + "." + g.getDefaultSaveDirectoryName());
        }
    }
}
