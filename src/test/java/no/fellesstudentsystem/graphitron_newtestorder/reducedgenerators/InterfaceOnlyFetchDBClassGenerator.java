package no.fellesstudentsystem.graphitron_newtestorder.reducedgenerators;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchInterfaceImplementationDBMethodGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

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
