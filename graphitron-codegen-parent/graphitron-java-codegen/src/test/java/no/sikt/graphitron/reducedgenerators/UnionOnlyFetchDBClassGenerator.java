package no.sikt.graphitron.reducedgenerators;

import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.generators.db.FetchMappedObjectDBMethodGenerator;
import no.sikt.graphitron.generators.db.FetchMultiTableDBMethodGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class UnionOnlyFetchDBClassGenerator extends DBClassGenerator {
    public UnionOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName(),
                List.of(
                        new FetchMultiTableDBMethodGenerator(target, processedSchema),
                        new FetchMappedObjectDBMethodGenerator(target, processedSchema)
                )
        ).build();
    }
}
