package no.sikt.graphitron.reducedgenerators;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchMultiTableDBMethodGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchNodeImplementationDBMethodGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchSingleTableInterfaceDBMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class InterfaceOnlyFetchDBClassGenerator extends FetchDBClassGenerator {
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
