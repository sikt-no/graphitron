package no.sikt.graphitron.generators.dto;

import com.apollographql.federation.graphqljava.Federation;
import no.sikt.graphitron.javapoet.TypeSpec;
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
                .filter(it -> !it.getName().equalsIgnoreCase("link__purpose")) //Ignore the link__purpose enum required by the @link directive used by Apollo Federation
                .map(this::generate)
                .toList();
    }
}
