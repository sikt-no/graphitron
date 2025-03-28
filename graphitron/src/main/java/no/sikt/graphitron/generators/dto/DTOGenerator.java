package no.sikt.graphitron.generators.dto;

import com.palantir.javapoet.*;
import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.StringUtils;

import javax.lang.model.element.Modifier;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

import static no.sikt.graphitron.configuration.GeneratorConfig.generatedModelsPackage;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static org.apache.commons.lang3.StringUtils.capitalize;

public abstract class DTOGenerator extends AbstractClassGenerator {
    public static final String HASH_CODE = "hashCode";
    public static final String OBJ = "obj";
    public static final String EQUALS = "equals";
    protected final ProcessedSchema processedSchema;

    public DTOGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    protected TypeName getTypeNameForField(FieldSpecification field) {
        TypeName typeClass = field.getTypeClass();

        if (typeClass == null) {
            typeClass = ClassName.get(generatedModelsPackage(), field.getTypeName());
        }

        if (field.isIterableWrapped()) {
            typeClass = ParameterizedTypeName.get(
                    ClassName.get(List.class), typeClass);
        }
        return typeClass;
    }

    protected TypeSpec.Builder getTypeSpecBuilder(String targetName, List<? extends GenerationSourceField<?>> fields) {
        var classBuilder = TypeSpec.classBuilder(targetName)
                .addModifiers(Modifier.PUBLIC)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build());

        classBuilder.addSuperinterface(ClassName.get(Serializable.class));

        var constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);

        var filteredFields = fields.stream()
                .filter(it -> !(it.isGeneratedWithResolver() || it.isExplicitlyNotGenerated()))
                .toList();

        filteredFields
                .forEach(field -> {
                    var fieldType = getTypeNameForField(field);
                    classBuilder.addField(fieldType, field.getName(), Modifier.PRIVATE);
                    constructorBuilder.addParameter(ParameterSpec.builder(fieldType, field.getName()).build());
                    constructorBuilder.addStatement("this.$N = $N", field.getName(), field.getName());

                    classBuilder.addMethod(
                            MethodSpec.methodBuilder("set" + capitalize(field.getName()))
                                    .addParameter(fieldType, field.getName())
                                    .addStatement("this.$N = $N", field.getName(), field.getName())
                                    .addModifiers(Modifier.PUBLIC)
                                    .build()
                    );

                    classBuilder.addMethod(
                            MethodSpec.methodBuilder("get" + capitalize(field.getName()))
                                    .addStatement("return $N", field.getName())
                                    .returns(fieldType)
                                    .addModifiers(Modifier.PUBLIC)
                                    .build()
                    );
                });

        var fieldNames = filteredFields.stream().map(AbstractField::getName).toList();

        return classBuilder
                .addMethod(constructorBuilder.build())
                .addMethod(getHashCodeMethod(fieldNames))
                .addMethod(getEqualsMethod(targetName, fieldNames));
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
