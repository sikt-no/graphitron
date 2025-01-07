package no.sikt.graphitron.reducedgenerators;

import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.datafetchers.queries.fetch.EntityDBFetcherMethodGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

public class EntityFetchOnlyDBClassGenerator extends FetchDBClassGenerator {
    public EntityFetchOnlyDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(target.getName(), new EntityDBFetcherMethodGenerator(target, processedSchema)).build();
    }
}
