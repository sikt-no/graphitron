package no.sikt.graphitron.generators.datafetcherresolvers.fetch;

import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITIES_FIELD;

/**
 * Class generator for basic select resolver classes.
 */
public class EntityFetcherResolverClassGenerator extends DataFetcherClassGenerator<ObjectDefinition> {
    public final String SAVE_DIRECTORY_NAME = "query", CLASS_NAME = "Entity";

    public EntityFetcherResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateTypeSpecs() {
        var query = processedSchema.getQueryType();
        if (query == null || !query.isGeneratedWithResolver() || !query.hasField(FEDERATION_ENTITIES_FIELD.getName())) {
            return List.of();
        }
        return List.of(generate(query));
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName() + CLASS_NAME,
                List.of(new EntityFetcherResolverMethodGenerator(target, processedSchema))
        ).build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DataFetcherClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
