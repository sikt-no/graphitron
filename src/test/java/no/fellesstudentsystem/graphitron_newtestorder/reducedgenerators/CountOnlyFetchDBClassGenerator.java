package no.fellesstudentsystem.graphitron_newtestorder.reducedgenerators;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchCountDBMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;

public class CountOnlyFetchDBClassGenerator extends FetchDBClassGenerator {
    public CountOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(target.getName(), List.of(new FetchCountDBMethodGenerator(target, processedSchema))).build();
    }
}
