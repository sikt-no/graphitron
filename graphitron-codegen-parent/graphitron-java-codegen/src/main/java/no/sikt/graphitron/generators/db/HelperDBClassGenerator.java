package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class HelperDBClassGenerator extends DBClassGenerator {
    public HelperDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        TypeSpec typeSpec = getSpec(
                target.getName(),
                List.of(
                        new HelperDBMethodGenerator(target, processedSchema),
                        new NodeHelperDBMethodGenerator(target, processedSchema, objectFieldsReturningNode)
                )
        ).build();
        warnOrCrashIfMethodsExceedsBounds(typeSpec);
        return typeSpec;
    }
}
