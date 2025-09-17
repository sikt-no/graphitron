package no.sikt.graphitron.generators.dto;

import graphql.language.NamedNode;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.generators.codebuilding.KeyWrapper;
import no.sikt.graphitron.javapoet.*;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.StringUtils;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.configuration.GeneratorConfig.generatedModelsPackage;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.KeyWrapper.getKeyMapForResolverFields;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.RESOLVER_KEY_DTO_SUFFIX;
import static no.sikt.graphitron.mappings.JavaPoetClassName.LIST;
import static org.apache.commons.lang3.StringUtils.capitalize;

public abstract class DTOGenerator extends AbstractClassGenerator {
    public static final String HASH_CODE = "hashCode", OBJ = "obj", EQUALS = "equals";
    protected final ProcessedSchema processedSchema;

    public DTOGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    protected TypeSpec.Builder getTypeSpecBuilder(String targetName, List<? extends GenerationSourceField<?>> fields) {
        var isJavaRecord = processedSchema.hasJavaRecord(targetName);
        var keyMap = getKeyMapForResolverFields(fields, processedSchema);
        var classBuilder = TypeSpec.classBuilder(targetName)
                .addModifiers(Modifier.PUBLIC)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build());

        var constructorBuilder = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);

        // New constructor that skips error fields so queries can use them without making up new empty fields.
        var constructorNoErrorsBuilder = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);

        List<String> allVariableNames = new ArrayList<>();

        var hasErrors = fields.stream().anyMatch(processedSchema::isExceptionOrExceptionUnion);

        if (!isJavaRecord) {
            keyMap.values()
                    .stream()
                    .distinct()
                    .forEach((key) -> {
                        var dtoVarName = key.getDTOVariableName();
                        allVariableNames.add(dtoVarName);
                        var typeName = key.getRecordTypeName();
                        addClassKeyVariable(key.getRowTypeName(), dtoVarName, classBuilder);
                        addConstructorKeyVariable(typeName, dtoVarName, constructorBuilder);
                        if (hasErrors) {
                            addConstructorKeyVariable(typeName, dtoVarName, constructorNoErrorsBuilder);
                        }
                    });
        }

        fields.stream()
                .filter(it -> !(it.isExplicitlyNotGenerated()))
                .forEach(field -> {
                    var key = keyMap.getOrDefault(field.getName(), null);
                    var variableName = getDTOVariableNameForField(field);
                    var typeName = getTypeNameForField(field, key);
                    var setValue = field.isResolver() ? key.getDTOVariableName() + ".valuesRow()" : field.getName();
                    RecordObjectSpecification<?> recordType = processedSchema.getRecordType(field.getContainerTypeName());
                    boolean hasTable = recordType != null && !recordType.hasTable() && field.isResolver();
                    boolean isList = field.isIterableWrapped() && hasTable;

                    allVariableNames.add(variableName);

                    addClassFieldVariable(typeName, variableName, classBuilder, field.isResolver());
                    addConstructorFieldVariable(typeName, variableName, constructorBuilder, setValue, false, field.isResolver(), isList);
                    if (hasErrors) {
                        addConstructorFieldVariable(
                                typeName,
                                variableName,
                                constructorNoErrorsBuilder,
                                setValue,
                                processedSchema.isExceptionOrExceptionUnion(field),
                                field.isResolver(),
                                isList
                        );
                    }
                });

        if (hasErrors) {
            classBuilder.addMethod(constructorNoErrorsBuilder.build());
        }
        return classBuilder
                .addMethodIf(!isJavaRecord, constructorBuilder.build())
                .addMethod(getHashCodeMethod(allVariableNames))
                .addMethod(getEqualsMethod(targetName, allVariableNames));
    }

    protected static String getDTOVariableNameForField(GenerationSourceField<? extends NamedNode<?>> field) {
        return field.isResolver() ? field.getName() + RESOLVER_KEY_DTO_SUFFIX : field.getName();
    }

    public static String getDTOGetterMethodNameForField(GenerationSourceField<?> field) {
        return "get" + capitalize(getDTOVariableNameForField(field));
    }

    private void addClassKeyVariable(TypeName fieldType, String name, TypeSpec.Builder classBuilder) {
        classBuilder
                .addField(fieldType, name, Modifier.PRIVATE)
                .addMethod(getGetterMethod(fieldType, name));
    }

    private void addConstructorKeyVariable(TypeName fieldType, String name, MethodSpec.Builder constructorBuilder) {
        constructorBuilder
                .addStatement("this.$N = $N.valuesRow()", name, name)
                .addParameter(fieldType, name);
    }

    private void addClassFieldVariable(TypeName fieldType, String name, TypeSpec.Builder classBuilder, boolean isResolver) {
        classBuilder
                .addField(fieldType, name, Modifier.PRIVATE)
                .addMethod(getGetterMethod(fieldType, name))
                .addMethod(getSetterMethod(fieldType, name));
    }

    private void addConstructorFieldVariable(TypeName fieldType, String name, MethodSpec.Builder constructorBuilder, String setValueInConstructor, boolean isError, boolean isResolver, boolean isList) {
        constructorBuilder
                .addStatementIf(isList, "this.$N = $T.of($N)", name, LIST.className, isError ? "null" : setValueInConstructor)
                .addStatementIf(!isList, "this.$N = $N", name, isError ? "null" : setValueInConstructor)
                .addParameterIf(!isResolver && !isError, fieldType, name);
    }

    private MethodSpec getGetterMethod(TypeName fieldType, String fieldName) {
        return MethodSpec
                .methodBuilder("get" + capitalize(fieldName))
                .addCode(returnWrap(fieldName))
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

    protected TypeName getTypeNameForField(GenerationSourceField<?> field, KeyWrapper firstStepKeyForField) {
        if (!field.isGeneratedWithResolver()) {
            TypeName typeClass = field.getTypeClass() != null ?
                    field.getTypeClass()
                    : ClassName.get(generatedModelsPackage(), field.getTypeName());

            return field.isIterableWrapped() ?
                    ParameterizedTypeName.get(ClassName.get(List.class), typeClass)
                    : typeClass;

        } else { // Fields with @splitQuery
            return firstStepKeyForField.getRowTypeName(processedSchema.isOrderedMultiKeyQuery(field));
        }
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
