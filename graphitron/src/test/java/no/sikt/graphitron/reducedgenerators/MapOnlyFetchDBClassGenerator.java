package no.sikt.graphitron.reducedgenerators;

import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchMappedObjectDBMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

public class MapOnlyFetchDBClassGenerator extends FetchDBClassGenerator {
    public MapOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(target.getName(), new FetchMappedObjectDBMethodGenerator(target, processedSchema)).build();
    }
}
