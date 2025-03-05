package no.sikt.graphitron.reducedgenerators;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.fetch.FetchCountDBMethodGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

public class CountOnlyFetchDBClassGenerator extends FetchDBClassGenerator {
    public CountOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(target.getName(), new FetchCountDBMethodGenerator(target, processedSchema)).build();
    }
}
