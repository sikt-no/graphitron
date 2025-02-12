package no.sikt.graphitron.generate;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.TypeRegistryClassGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.sikt.graphitron.generators.exception.ExceptionToErrorMappingProviderGenerator;
import no.sikt.graphitron.generators.exception.MutationExceptionStrategyConfigurationGenerator;
import no.sikt.graphitron.generators.mapping.JavaRecordMapperClassGenerator;
import no.sikt.graphitron.generators.mapping.RecordMapperClassGenerator;
import no.sikt.graphitron.generators.mapping.TransformerClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.EntityFetcherClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.FetchClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.typeresolvers.TypeResolverClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.update.UpdateClassGenerator;
import no.sikt.graphitron.generators.resolvers.kickstart.fetch.FetchResolverClassGenerator;
import no.sikt.graphitron.generators.resolvers.kickstart.update.UpdateResolverClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphql.schema.SchemaReadingHelper.getTypeDefinitionRegistry;

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
        generate(getGenerators(processedSchema));
    }

    public static List<ClassGenerator> getGenerators(ProcessedSchema processedSchema) {
        List<ClassGenerator> generators = List.of(
                new FetchDBClassGenerator(processedSchema),
                new FetchResolverClassGenerator(processedSchema),
                new UpdateResolverClassGenerator(processedSchema),
                new FetchClassGenerator(processedSchema),
                new UpdateClassGenerator(processedSchema),
                new UpdateDBClassGenerator(processedSchema),
                new TransformerClassGenerator(processedSchema),
                new RecordMapperClassGenerator(processedSchema, true),
                new RecordMapperClassGenerator(processedSchema, false),
                new JavaRecordMapperClassGenerator(processedSchema, true),
                new JavaRecordMapperClassGenerator(processedSchema, false),
                new MutationExceptionStrategyConfigurationGenerator(processedSchema),
                new ExceptionToErrorMappingProviderGenerator(processedSchema),
                new EntityFetcherClassGenerator(processedSchema),
                new TypeResolverClassGenerator(processedSchema),
                new TypeRegistryClassGenerator()
        );
        return Stream.concat(
                generators.stream(),
                Stream.of(new WiringClassGenerator(generators, processedSchema.nodeExists()))  // This one must be the last generator.
        ).toList();
    }

    /**
     * Run a list of generators.
     * @param generators The generators that should be executed.
     */
    public static void generate(List<ClassGenerator> generators) {
        for (var g : generators) {
            g.generateAllToDirectory(GeneratorConfig.outputDirectory(), GeneratorConfig.outputPackage());
            LOGGER.info("Generated sources to: {}.{}", GeneratorConfig.outputPackage(), g.getDefaultSaveDirectoryName());
        }
    }

    public static Map<String, List<String>> generateAsStrings(List<ClassGenerator> generators) {
        return generators
                .stream()
                .flatMap(it -> it.generateAllAsMap().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, it -> List.of(it.getValue().split("\n"))));
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
