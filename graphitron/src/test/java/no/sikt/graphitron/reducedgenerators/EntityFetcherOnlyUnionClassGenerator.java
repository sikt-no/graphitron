package no.sikt.graphitron.reducedgenerators;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.EntityFetcherClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.EntityTypeResolverMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

public class EntityFetcherOnlyUnionClassGenerator extends EntityFetcherClassGenerator {
    public EntityFetcherOnlyUnionClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }
    @Override
    public TypeSpec generate(ObjectDefinition dummy) {
        return getSpec(processedSchema.getQueryType().getName() + CLASS_NAME, new EntityTypeResolverMethodGenerator(processedSchema)).build();
    }
}
