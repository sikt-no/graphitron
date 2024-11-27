package no.sikt.graphitron.reducedgenerators;

import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchInterfaceImplementationDBMethodGenerator;
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
                List.of(new FetchInterfaceImplementationDBMethodGenerator(target, processedSchema, interfacesReturnedByObjectField))
        ).build();
    }
}
