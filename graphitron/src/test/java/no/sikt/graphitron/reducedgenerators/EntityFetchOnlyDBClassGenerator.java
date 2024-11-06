package no.sikt.graphitron.reducedgenerators;

import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.datafetcherqueries.fetch.EntityDBFetcherMethodGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class EntityFetchOnlyDBClassGenerator extends FetchDBClassGenerator {
    public EntityFetchOnlyDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(target.getName(), List.of(new EntityDBFetcherMethodGenerator(target, processedSchema))).build();
    }
}
