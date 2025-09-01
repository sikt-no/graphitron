package no.sikt.graphitron.generators.dto;

import no.sikt.graphitron.definitions.objects.InterfaceDefinition;
import no.sikt.graphitron.generators.codebuilding.KeyWrapper;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.UniqueKey;

import javax.lang.model.element.Modifier;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.KeyWrapper.getKeyMapForResolverFields;
import static org.apache.commons.lang3.StringUtils.capitalize;

public class InterfaceDTOGenerator extends DTOGenerator {

    public InterfaceDTOGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    protected TypeSpec generate(InterfaceDefinition target) {
        var interfaceBuilder = TypeSpec.interfaceBuilder(target.getName()).addModifiers(Modifier.PUBLIC);

        var keyMap = target.hasTable() ? getKeyMapForResolverFields(target.getFields(), processedSchema) : new HashMap<String, KeyWrapper>();

        (new LinkedHashSet<>(keyMap.values()))
                .forEach(( key) -> {
                    interfaceBuilder.addMethod(
                            MethodSpec.methodBuilder(key.getDTOGetterName())
                                    .returns(key.getRowTypeName())
                                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                                    .build()
                    );
                });

        var implementations = processedSchema.getImplementationsForInterface(target);

        target.getFields()
                .stream()
                .filter(it -> !it.isExplicitlyNotGenerated())
                .filter(it -> implementations.stream().noneMatch(impl -> impl.getFieldByName(it.getName()).isExplicitlyNotGenerated()))
                .filter(it -> !it.isGeneratedWithResolver() || target.hasTable()) // Skip splitQuery keys on multitable interface
                .forEach(field -> {
                    var fieldType = getTypeNameForField(field, keyMap.computeIfAbsent(field.getName(), it -> null));

                    interfaceBuilder.addMethod(
                            MethodSpec.methodBuilder("get" + capitalize(getDTOVariableNameForField(field)))
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
