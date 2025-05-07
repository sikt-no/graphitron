package no.sikt.graphitron.generators.dto;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.InputDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class InputDTOGenerator extends DTOGenerator {

    public InputDTOGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    protected TypeSpec generate(InputDefinition target) {
        return getTypeSpecBuilder(target.getName(), target.getFields()).build();
    }

    @Override
    public List<TypeSpec> generateAll() {
        return processedSchema
                .getInputTypes()
                .values()
                .stream()
                .map(this::generate)
                .toList();
    }
}
