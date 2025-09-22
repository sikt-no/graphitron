package no.sikt.graphitron.generators.datafetchers.operations;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.getFormatGeneratedName;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

/**
 * Class generator for any data fetchers classes.
 */
public class OperationClassGenerator extends DataFetcherClassGenerator<ObjectField> {
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
                .flatMap(it -> it.getFields().stream())
                .map(this::generate)
                .filter(it -> !it.methodSpecs().isEmpty())
                .toList();
    }

    @Override
    public TypeSpec generate(ObjectField target) {
        var generators = List.of(
                new OperationMethodGenerator(target, processedSchema),
                new FetchNodeMethodGenerator(target, processedSchema)
        );
        var classNameFormat = getFormatGeneratedName(target);
        var spec = getSpec(classNameFormat, generators);
        if (target.getTypeName().equals(NODE_TYPE.getName()) && !GeneratorConfig.shouldMakeNodeStrategy()) {
            buildNodeMap(target).ifPresent(spec::addField);
        }
        var className = getGeneratedClassName(classNameFormat);
        generators.forEach(it -> addFetchers(it.getDataFetcherWiring(), className));
        return spec.build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DataFetcherClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
