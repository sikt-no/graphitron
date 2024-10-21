package no.fellesstudentsystem.graphitron.reducedgenerators;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchMappedObjectDBMethodGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;

public class MapOnlyFetchDBClassGenerator extends FetchDBClassGenerator {
    public MapOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(target.getName(), List.of(new FetchMappedObjectDBMethodGenerator(target, processedSchema))).build();
    }
}
