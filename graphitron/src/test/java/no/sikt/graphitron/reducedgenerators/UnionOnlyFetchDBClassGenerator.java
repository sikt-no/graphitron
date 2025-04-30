package no.sikt.graphitron.reducedgenerators;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.fetch.*;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class UnionOnlyFetchDBClassGenerator extends FetchDBClassGenerator {
    public UnionOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName(),
                List.of(
                        new FetchMultiTableDBMethodGenerator(target, processedSchema),
                        new FetchMappedObjectDBMethodGenerator(target, processedSchema)
                )
        ).build();
    }
}
