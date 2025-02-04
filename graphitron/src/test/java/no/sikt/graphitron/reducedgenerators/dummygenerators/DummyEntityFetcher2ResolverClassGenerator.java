package no.sikt.graphitron.reducedgenerators.dummygenerators;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

/**
 * Class generator for wrapping a dummy entity resolver.
 */
public class DummyEntityFetcher2ResolverClassGenerator extends DummyEntityFetcherResolverClassGenerator {
    public DummyEntityFetcher2ResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition dummy) {
        var name = processedSchema.getQueryType().getName() + CLASS_NAME + "2";
        var fetcherGenerator = new DummyEntityFetcherResolverMethodGenerator(processedSchema);
        var spec = getSpec(name, fetcherGenerator).build();
        var className = getGeneratedClassName(name + getFileNameSuffix());
        addFetchers(fetcherGenerator.getDataFetcherWiring(), className);
        return spec;
    }
}
