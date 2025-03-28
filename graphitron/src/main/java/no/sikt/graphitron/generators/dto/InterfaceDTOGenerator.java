package no.sikt.graphitron.generators.dto;

import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.InterfaceDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.capitalize;

public class InterfaceDTOGenerator extends DTOGenerator {

    public InterfaceDTOGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    protected TypeSpec generate(InterfaceDefinition target) {
        var interfaceBuilder = TypeSpec.interfaceBuilder(target.getName()).addModifiers(Modifier.PUBLIC);

        target.getFields()
                .stream()
                .filter(it -> !(it.isGeneratedWithResolver() || it.isExplicitlyNotGenerated()))
                .forEach(field -> {
                    var fieldType = getTypeNameForField(field);

                    interfaceBuilder.addMethod(
                            MethodSpec.methodBuilder("get" + capitalize(field.getName()))
                                    .returns(fieldType)
                                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                                    .build()
                    );
                });
        return interfaceBuilder.build();
    }

    @Override
    public List<TypeSpec> generateAll() {
        return processedSchema
                .getInterfaces()
                .values()
                .stream()
                .map(this::generate)
                .toList();
    }
}
