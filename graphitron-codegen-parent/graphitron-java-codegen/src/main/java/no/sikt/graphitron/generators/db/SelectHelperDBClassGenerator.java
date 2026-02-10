package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class SelectHelperDBClassGenerator extends DBClassGenerator {
    public SelectHelperDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        TypeSpec typeSpec = getSpec(
                target.getName(),
                List.of(
                        new SelectHelperDBMethodGenerator(target, processedSchema),
                        new NodeSelectHelperDBMethodGenerator(target, processedSchema, objectFieldsReturningNode)
                )
        ).build();
        warnOrCrashIfMethodsExceedsBounds(typeSpec);
        return typeSpec;
    }
}
