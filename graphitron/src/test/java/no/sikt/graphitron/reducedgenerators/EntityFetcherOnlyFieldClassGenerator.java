package no.sikt.graphitron.reducedgenerators;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.EntityFetcherClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.EntityFetcherMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

public class EntityFetcherOnlyFieldClassGenerator extends EntityFetcherClassGenerator {
    public EntityFetcherOnlyFieldClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition dummy) {
        return getSpec(processedSchema.getQueryType().getName() + CLASS_NAME, new EntityFetcherMethodGenerator(processedSchema)).build();
    }
}
