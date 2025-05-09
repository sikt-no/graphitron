package no.sikt.graphitron.reducedgenerators;

import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.fetch.*;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class NodeStrategyInterfaceOnlyFetchDBClassGenerator extends FetchDBClassGenerator {
    public NodeStrategyInterfaceOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName(),
                List.of(
                        new FetchNodeImplementationDBMethodGenerator(target, processedSchema, objectFieldsReturningNode),
                        new FetchMappedObjectDBMethodGenerator(target, processedSchema)
                )
        ).build();
    }
}
