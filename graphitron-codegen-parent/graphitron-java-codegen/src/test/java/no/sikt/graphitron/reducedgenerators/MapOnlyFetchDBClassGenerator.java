package no.sikt.graphitron.reducedgenerators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.generators.db.FetchMappedObjectDBMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

public class MapOnlyFetchDBClassGenerator extends DBClassGenerator {
    public MapOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(target.getName(), new FetchMappedObjectDBMethodGenerator(target, processedSchema)).build();
    }
}
