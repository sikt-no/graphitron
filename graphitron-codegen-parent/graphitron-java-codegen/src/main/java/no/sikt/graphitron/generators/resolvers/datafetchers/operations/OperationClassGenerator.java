package no.sikt.graphitron.generators.resolvers.datafetchers.operations;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

/**
 * Class generator for any data fetchers classes.
 */
public class OperationClassGenerator extends DataFetcherClassGenerator<ObjectDefinition> {
    public final String SAVE_DIRECTORY_NAME = "operations";

    public OperationClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateAll() {
        return processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(ObjectDefinition::isGenerated)
                .map(this::generate)
                .filter(it -> !it.methodSpecs().isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        var generators = List.of(
                new OperationMethodGenerator(target, processedSchema),
                new FetchNodeMethodGenerator(target, processedSchema)
        );
        var spec = getSpec(target.getName(), generators);
        target
                .getFields()
                .stream()
                .filter(it -> it.getTypeName().equals(NODE_TYPE.getName()) && !GeneratorConfig.shouldMakeNodeStrategy())
                .findFirst()
                .flatMap(this::buildNodeMap)
                .ifPresent(spec::addField);
        var className = getGeneratedClassName(target.getName() + getFileNameSuffix());
        generators.forEach(it -> addFetchers(it.getDataFetcherWiring(), className));
        return spec.build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DataFetcherClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
