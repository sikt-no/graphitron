package no.sikt.graphitron.generate;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.CodeInterfaceClassGenerator;
import no.sikt.graphitron.generators.codeinterface.typeregistry.TypeRegistryClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringClassGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.sikt.graphitron.generators.dto.*;
import no.sikt.graphitron.generators.exception.ExceptionToErrorMappingProviderGenerator;
import no.sikt.graphitron.generators.exception.MutationExceptionStrategyConfigurationGenerator;
import no.sikt.graphitron.generators.frontgen.QueryComponentsGenerator;
import no.sikt.graphitron.generators.frontgen.TableUIComponentGenerator;
import no.sikt.graphitron.generators.mapping.JavaRecordMapperClassGenerator;
import no.sikt.graphitron.generators.mapping.RecordMapperClassGenerator;
import no.sikt.graphitron.generators.mapping.TransformerClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.operations.EntityFetcherClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.typeresolvers.TypeResolverClassGenerator;
import no.sikt.graphitron.generators.resolvers.kickstart.fetch.FetchResolverClassGenerator;
import no.sikt.graphitron.generators.resolvers.kickstart.update.UpdateResolverClassGenerator;
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
        var processedSchema = getProcessedSchema(true);
        processedSchema.validate();
        generate(getGenerators(processedSchema));
    }

    public static List<ClassGenerator> getGenerators(ProcessedSchema processedSchema) {
        List<ClassGenerator> generators = List.of(
                new TableUIComponentGenerator(processedSchema),
                new QueryComponentsGenerator(processedSchema),
                new TypeDTOGenerator(processedSchema),
                new InputDTOGenerator(processedSchema),
                new InterfaceDTOGenerator(processedSchema),
                new UnionDTOGenerator(processedSchema),
                new EnumDTOGenerator(processedSchema),
                new FetchDBClassGenerator(processedSchema),
                new UpdateDBClassGenerator(processedSchema),
                new OperationClassGenerator(processedSchema),
                new TransformerClassGenerator(processedSchema),
                new RecordMapperClassGenerator(processedSchema, true),
                new RecordMapperClassGenerator(processedSchema, false),
                new JavaRecordMapperClassGenerator(processedSchema, true),
                new JavaRecordMapperClassGenerator(processedSchema, false),
                new MutationExceptionStrategyConfigurationGenerator(processedSchema),
                new ExceptionToErrorMappingProviderGenerator(processedSchema),
                new EntityFetcherClassGenerator(processedSchema),
                new TypeResolverClassGenerator(processedSchema),
                new TypeRegistryClassGenerator(),
                new CodeInterfaceClassGenerator(processedSchema)
        );
        List<ClassGenerator> kickstartGenerators = GeneratorConfig.shouldMakeKickstart() ? List.of(
                new FetchResolverClassGenerator(processedSchema),
                new UpdateResolverClassGenerator(processedSchema)
        ) : List.of();
        var allGenerators = Stream.concat(generators.stream(), kickstartGenerators.stream());
        return Stream.concat(
                allGenerators,
                Stream.of(new WiringClassGenerator(generators, processedSchema))  // This one must be the last generator.
        ).toList();
    }

    /**
     * Run a list of generators.
     * @param generators The generators that should be executed.
     */
    public static void generate(List<ClassGenerator> generators) {
        var last = generators.subList(generators.size() - 1, generators.size()).get(0); // The wiring generator must go last.
        var other = generators.subList(0, generators.size() - 1);
        other.stream().parallel().forEach(g -> {
            g.generateAllToDirectory(GeneratorConfig.outputDirectory(), GeneratorConfig.outputPackage());
            LOGGER.info("Generated sources to: {}.{}", GeneratorConfig.outputPackage(), g.getDefaultSaveDirectoryName());
        });
        last.generateAllToDirectory(GeneratorConfig.outputDirectory(), GeneratorConfig.outputPackage());
        LOGGER.info("Generated sources to: {}.{}", GeneratorConfig.outputPackage(), last.getDefaultSaveDirectoryName());
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
        return getProcessedSchema(false);
    }

    /**
     * @param verbose Should the files read be logged?
     * @return A Graphitron-interpreted version of the schema files set in {@link GeneratorConfig}.
     */
    public static ProcessedSchema getProcessedSchema(boolean verbose) {
        var schemaLocations = GeneratorConfig.generatorSchemaFiles();
        if (verbose) {
            LOGGER.info("Reading graphql schemas {}", schemaLocations);
        }
        return new ProcessedSchema(getTypeDefinitionRegistry(schemaLocations));
    }
}
