package no.sikt.graphitron.generators.dto;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.EnumDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;

public class EnumDTOGenerator extends DTOGenerator {

    public EnumDTOGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    protected TypeSpec generate(EnumDefinition enumDefinition) {
        var enumBuilder = TypeSpec.enumBuilder(enumDefinition.getName()).addModifiers(Modifier.PUBLIC);
        enumDefinition.getFields().forEach(field -> {
            enumBuilder.addEnumConstant(field.getName());
        });

        return enumBuilder.build();
    }

    @Override
    public List<TypeSpec> generateAll() {
        return processedSchema
                .getEnums()
                .values()
                .stream()
                .map(this::generate)
                .toList();
    }
}
