package no.sikt.graphitron.reducedgenerators;

import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.generators.db.FetchTableRecordDBMethodGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class FetchTableRecordOnlyDBClassGenerator extends DBClassGenerator {
    public FetchTableRecordOnlyDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(target.getName(),
                List.of(new FetchTableRecordDBMethodGenerator(target, processedSchema))
        ).build();
    }
}
