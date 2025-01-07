package no.sikt.graphitron.reducedgenerators.dummygenerators;

import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.datafetchers.resolvers.fetch.EntityFetcherResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

/**
 * Class generator for wrapping a dummy entity resolver.
 */
public class DummyEntityFetcherResolverClassGenerator extends EntityFetcherResolverClassGenerator {
    public DummyEntityFetcherResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition dummy) {
        var query = processedSchema.getQueryType();
        var name = query.getName() + CLASS_NAME;
        var fetcherGenerator = new DummyEntityFetcherResolverMethodGenerator(processedSchema);
        var typeResolver = new DummyEntityTypeResolverMethodGenerator(processedSchema);
        var spec = getSpec(name, List.of(fetcherGenerator, typeResolver)).build();
        var className = getGeneratedClassName(name + getFileNameSuffix());
        addFetchers(fetcherGenerator.getDataFetcherWiring(), className);
        addTypeResolvers(typeResolver.getTypeResolverWiring(), className);
        return spec;
    }
}
