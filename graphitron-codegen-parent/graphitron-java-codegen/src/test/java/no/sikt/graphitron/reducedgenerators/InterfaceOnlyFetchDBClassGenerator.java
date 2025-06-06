package no.sikt.graphitron.reducedgenerators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.generators.db.FetchMultiTableDBMethodGenerator;
import no.sikt.graphitron.generators.db.FetchNodeImplementationDBMethodGenerator;
import no.sikt.graphitron.generators.db.FetchSingleTableInterfaceDBMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class InterfaceOnlyFetchDBClassGenerator extends DBClassGenerator {
    public InterfaceOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName(),
                List.of(
                        new FetchNodeImplementationDBMethodGenerator(target, processedSchema, objectFieldsReturningNode),
                        new FetchMultiTableDBMethodGenerator(target, processedSchema),
                        new FetchSingleTableInterfaceDBMethodGenerator(target, processedSchema)
                )
        ).build();
    }
}
