package no.sikt.graphitron.generators.dto;

import no.sikt.graphitron.definitions.objects.UnionDefinition;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;

public class UnionDTOGenerator extends DTOGenerator<UnionDefinition> {

    public UnionDTOGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(UnionDefinition target) {
        return TypeSpec.interfaceBuilder(target.getName()).addModifiers(Modifier.PUBLIC).build();
    }

    @Override
    public List<TypeSpec> generateAll() {
        return processedSchema
                .getUnions()
                .values()
                .stream()
                .map(this::generate)
                .toList();
    }
}
