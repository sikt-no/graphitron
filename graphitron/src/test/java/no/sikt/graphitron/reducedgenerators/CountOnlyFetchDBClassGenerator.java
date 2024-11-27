package no.sikt.graphitron.reducedgenerators;

import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.fetch.FetchCountDBMethodGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

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
