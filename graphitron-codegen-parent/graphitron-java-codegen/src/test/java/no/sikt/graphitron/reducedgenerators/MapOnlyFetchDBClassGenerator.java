package no.sikt.graphitron.reducedgenerators;

import no.sikt.graphitron.generators.db.HelperDBMethodGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.generators.db.FetchMappedObjectDBMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class MapOnlyFetchDBClassGenerator extends DBClassGenerator {
    public MapOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName(),
                List.of(
                        new FetchMappedObjectDBMethodGenerator(target, processedSchema),
                        new HelperDBMethodGenerator(target, processedSchema)
                )
        ).build();
    }
}
