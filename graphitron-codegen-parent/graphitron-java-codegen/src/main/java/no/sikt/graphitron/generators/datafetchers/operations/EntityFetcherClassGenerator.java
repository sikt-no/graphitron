package no.sikt.graphitron.generators.datafetchers.operations;

import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.ENTITY_CLASS;

/**
 * Class generator for wrapping the entity resolver.
 */
public class EntityFetcherClassGenerator extends DataFetcherClassGenerator<ObjectDefinition> {
    public static final String SAVE_DIRECTORY_NAME = "operations";

    public EntityFetcherClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateAll() {
        if (!processedSchema.hasEntitiesField() || !processedSchema.getQueryType().isGeneratedWithResolver()) {
            return List.of();
        }
        return List.of(generate(null));
    }

    @Override
    public TypeSpec generate(ObjectDefinition dummy) {
        var query = processedSchema.getQueryType();
        var name = query.getName() + ENTITY_CLASS;
        var fetcherGenerator = new EntityFetcherMethodGenerator(processedSchema);
        var spec = getSpec(name, fetcherGenerator).build();
        var className = getGeneratedClassName(name);
        addFetchers(fetcherGenerator.getDataFetcherWiring(), className);
        return spec;
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DataFetcherClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
