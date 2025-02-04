package no.sikt.graphitron.generators.datafetchers.resolvers.fetch;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

/**
 * Class generator for wrapping the entity resolver.
 */
public class EntityFetcherResolverClassGenerator extends DataFetcherClassGenerator<ObjectDefinition> {
    public static final String SAVE_DIRECTORY_NAME = "query", CLASS_NAME = "Entity";

    public EntityFetcherResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateTypeSpecs() {
        if (!processedSchema.hasEntitiesField() || !processedSchema.getQueryType().isGeneratedWithResolver()) {
            return List.of();
        }
        return List.of(generate(null));
    }

    @Override
    public TypeSpec generate(ObjectDefinition dummy) {
        var query = processedSchema.getQueryType();
        var name = query.getName() + CLASS_NAME;
        var fetcherGenerator = new EntityFetcherResolverMethodGenerator(processedSchema);
        var typeResolver = new EntityTypeResolverMethodGenerator(processedSchema);
        var spec = getSpec(name, List.of(fetcherGenerator, typeResolver)).build();
        var className = getGeneratedClassName(name + getFileNameSuffix());
        addFetchers(fetcherGenerator.getDataFetcherWiring(), className);
        addTypeResolvers(typeResolver.getTypeResolverWiring(), className);
        return spec;
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DataFetcherClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
