package no.sikt.graphitron.generators.dto;

import no.sikt.graphitron.javapoet.*;
import graphql.language.NamedNode;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.StringUtils;
import org.jooq.Key;
import org.jooq.UniqueKey;

import javax.lang.model.element.Modifier;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.configuration.GeneratorConfig.generatedModelsPackage;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.ResolverKeyHelpers.getKeyMapForResolverFields;
import static no.sikt.graphitron.generators.codebuilding.ResolverKeyHelpers.getKeyTypeName;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public abstract class DTOGenerator extends AbstractClassGenerator {
    public static final String HASH_CODE = "hashCode", OBJ = "obj", EQUALS = "equals", PRIMARY_KEY = "primaryKey";
    protected final ProcessedSchema processedSchema;

    public DTOGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    protected TypeSpec.Builder getTypeSpecBuilder(String targetName, List<? extends GenerationSourceField<?>> fields) {
        var keyMap = getKeyMapForResolverFields(fields, processedSchema);
        var classBuilder = TypeSpec.classBuilder(targetName)
                .addModifiers(Modifier.PUBLIC)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build());

        classBuilder.addSuperinterface(ClassName.get(Serializable.class));

        var constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);

        List<String> allVariableNames = new ArrayList<>();

        keyMap.values()
                .stream()
                .distinct()
                .forEach((key) -> {
                    allVariableNames.add(getDTOVariableNameForKey(key));
                    addKeyVariable(getKeyTypeName(key), getDTOVariableNameForKey(key), classBuilder, constructorBuilder);
                });

        fields.stream()
                .filter(it -> !(it.isExplicitlyNotGenerated()))
                .forEach(field -> {
                    var key = keyMap.getOrDefault(field.getName(), null);
                    var variableName = getDTOVariableNameForField(field);
                    allVariableNames.add(variableName);

                    addFieldVariable(
                            getTypeNameForField(field, key),
                            variableName,
                            classBuilder,
                            constructorBuilder,
                            field.isResolver() ? getDTOVariableNameForKey(key) : field.getName(),
                            field.isResolver()
                    );
                });

        return classBuilder
                .addMethod(constructorBuilder.build())
                .addMethod(getHashCodeMethod(allVariableNames))
                .addMethod(getEqualsMethod(targetName, allVariableNames));
    }

    protected static String getDTOVariableNameForKey(Key<?> key) {
        return key instanceof UniqueKey ? PRIMARY_KEY : uncapitalize(key.getName());
    }

    protected static String getDTOVariableNameForField(GenerationSourceField<? extends NamedNode<?>> field) {
        return field.isResolver() ? field.getName() + "Key" : field.getName();
    }

    protected TypeName getTypeNameForField(GenerationSourceField<?> field, Key<?> firstStepKeyForField) {
        if (!field.isGeneratedWithResolver()) {
            TypeName typeClass = field.getTypeClass() != null ?
                    field.getTypeClass()
                    : ClassName.get(generatedModelsPackage(), field.getTypeName());

            return field.isIterableWrapped() ?
                    ParameterizedTypeName.get(ClassName.get(List.class), typeClass)
                    : typeClass;

        } else { // Fields with @splitQuery
            return getKeyTypeName(firstStepKeyForField);
        }
    }

    private void addKeyVariable(TypeName fieldType, String keyVariableName, TypeSpec.Builder classBuilder, MethodSpec.Builder constructorBuilder) {
        addVariable(fieldType, keyVariableName, classBuilder, constructorBuilder, keyVariableName);
        classBuilder.addMethod(getGetterMethod(fieldType, keyVariableName));
        constructorBuilder.addParameter(ParameterSpec.builder(fieldType, keyVariableName).build());
    }

    private void addFieldVariable(TypeName fieldType, String fieldName, TypeSpec.Builder classBuilder, MethodSpec.Builder constructorBuilder, String setValueInConstructor, boolean isResolver) {
        addVariable(fieldType, fieldName, classBuilder, constructorBuilder, setValueInConstructor);
        classBuilder.addMethod(getGetterMethod(fieldType, fieldName));

        if (!isResolver) {
            constructorBuilder.addParameter(ParameterSpec.builder(fieldType, fieldName).build());
            classBuilder.addMethod(getSetterMethod(fieldType, fieldName));
        }
    }

    private void addVariable(TypeName fieldType, String fieldName, TypeSpec.Builder classBuilder, MethodSpec.Builder constructorBuilder, String setValueInConstructor) {
        classBuilder.addField(fieldType, fieldName, Modifier.PRIVATE);
        constructorBuilder.addStatement("this.$N = $N", fieldName, setValueInConstructor);
    }

    private MethodSpec getGetterMethod(TypeName fieldType, String fieldName) {
        return MethodSpec.methodBuilder("get" + capitalize(fieldName))
                .addStatement("return $N", fieldName)
                .returns(fieldType)
                .addModifiers(Modifier.PUBLIC)
                .build();
    }

    private MethodSpec getSetterMethod(TypeName fieldType, String fieldName) {
        return MethodSpec.methodBuilder("set" + capitalize(fieldName))
                .addParameter(fieldType, fieldName)
                .addStatement("this.$N = $N", fieldName, fieldName)
                .addModifiers(Modifier.PUBLIC)
                .build();
    }

    private MethodSpec getHashCodeMethod(List<String> fieldNames) {
        return MethodSpec.methodBuilder(HASH_CODE)
                .returns(int.class)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addCode(returnWrap(
                        CodeBlock.of("$T.hash($L)",
                                Objects.class,
                                StringUtils.join(fieldNames, ", "))
                ))
                .build();
    }

    private MethodSpec getEqualsMethod(String targetName, List<String> fieldNames) {
        CodeBlock returnCodeBlock = CodeBlock.join(
                fieldNames.stream()
                        .map(field -> CodeBlock.of("$T.equals($N, that.$N)", Objects.class, field, field))
                        .toList(),
                " && ");

        return MethodSpec.methodBuilder(EQUALS)
                .returns(boolean.class)
                .addParameter(Object.class, OBJ)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .beginControlFlow("if (this == $N)", OBJ)
                .addCode(returnWrap("true"))
                .endControlFlow()
                .beginControlFlow("if ($N == null || getClass() != $N.getClass())", OBJ, OBJ)
                .addCode(returnWrap("false"))
                .endControlFlow()
                .addStatement("final $L that = ($L) $N", targetName, targetName, OBJ)
                .addCode(returnWrap(returnCodeBlock))
                .build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return "model";
    }

    @Override
    public String getFileNameSuffix() {
        return "";
    }
}
