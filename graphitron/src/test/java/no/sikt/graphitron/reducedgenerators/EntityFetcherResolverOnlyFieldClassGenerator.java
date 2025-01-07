package no.sikt.graphitron.reducedgenerators;

import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.datafetchers.resolvers.fetch.EntityFetcherResolverClassGenerator;
import no.sikt.graphitron.generators.datafetchers.resolvers.fetch.EntityFetcherResolverMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

public class EntityFetcherResolverOnlyFieldClassGenerator extends EntityFetcherResolverClassGenerator {
    public EntityFetcherResolverOnlyFieldClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition dummy) {
        return getSpec(processedSchema.getQueryType().getName() + CLASS_NAME, new EntityFetcherResolverMethodGenerator(processedSchema)).build();
    }
}
