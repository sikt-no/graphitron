package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractClassGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.MethodGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.util.List;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapSet;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.newSelectionSetConstructor;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.capitalize;

public class TransformerClassGenerator extends AbstractClassGenerator<ObjectDefinition> {
    public static final String
            FILE_NAME_SUFFIX = "RecordTransformer",
            METHOD_VALIDATE_NAME = "validate",
            METHOD_CONTEXT_NAME = "get" + capitalize(CONTEXT_NAME),
            METHOD_ENV_NAME = "get" + capitalize(VARIABLE_ENV),
            METHOD_SELECT_NAME = "get" + capitalize(VARIABLE_SELECT),
            METHOD_ARGS_NAME = "get" + capitalize(VARIABLE_ARGUMENTS),
            DEFAULT_SAVE_DIRECTORY_NAME = "transform";

    public TransformerClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName(),
                List.of(
                        new TransformerListMethodGenerator(target, processedSchema),
                        new TransformerMethodGenerator(target, processedSchema)
                )
        ).build();
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<MethodGenerator<? extends GenerationTarget>> generators) {
        return super
                .getSpec("", generators)
                .addField(DSL_CONTEXT.className, CONTEXT_NAME, Modifier.PRIVATE, Modifier.FINAL)
                .addField(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV, Modifier.PRIVATE, Modifier.FINAL)
                .addField(wrapSet(STRING.className), VARIABLE_ARGUMENTS, Modifier.PRIVATE, Modifier.FINAL)
                .addField(SELECTION_SET.className, VARIABLE_SELECT, Modifier.PRIVATE, Modifier.FINAL)
                .addField(FieldSpec.builder(ParameterizedTypeName.get(HASH_SET.className, GRAPHQL_ERROR.className), VALIDATION_ERRORS_NAME, Modifier.PRIVATE, Modifier.FINAL).initializer("new $T<$T>()", HASH_SET.className, GRAPHQL_ERROR.className).build())
                .addMethod(constructor())
                .addMethod(validate())
                .addMethod(getContext())
                .addMethod(getEnvironment())
                .addMethod(getSelectionSet())
                .addMethod(getArguments());
    }

    @NotNull
    private static MethodSpec constructor() {
        return MethodSpec
                .constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
                .addParameter(DSL_CONTEXT.className, CONTEXT_NAME)
                .addStatement("this.$N = $N", VARIABLE_ENV, VARIABLE_ENV)
                .addStatement("this.$N = $N", CONTEXT_NAME, CONTEXT_NAME)
                .addStatement("$N = $L", VARIABLE_SELECT, newSelectionSetConstructor())
                .addStatement("$N = $T.flattenArgumentKeys($N.getArguments())", VARIABLE_ARGUMENTS, ARGUMENTS.className, VARIABLE_ENV)
                .build();
    }

    @NotNull
    private static MethodSpec getContext() {
        return MethodSpec
                .methodBuilder(METHOD_CONTEXT_NAME)
                .addModifiers(Modifier.PUBLIC)
                .returns(DSL_CONTEXT.className)
                .addCode(returnWrap(CONTEXT_NAME))
                .build();
    }

    @NotNull
    private static MethodSpec getEnvironment() {
        return MethodSpec
                .methodBuilder(METHOD_ENV_NAME)
                .addModifiers(Modifier.PUBLIC)
                .returns(DATA_FETCHING_ENVIRONMENT.className)
                .addCode(returnWrap(VARIABLE_ENV))
                .build();
    }

    @NotNull
    private static MethodSpec getSelectionSet() {
        return MethodSpec
                .methodBuilder(METHOD_SELECT_NAME)
                .addModifiers(Modifier.PUBLIC)
                .returns(SELECTION_SET.className)
                .addCode(returnWrap(VARIABLE_SELECT))
                .build();
    }

    @NotNull
    private static MethodSpec getArguments() {
        return MethodSpec
                .methodBuilder(METHOD_ARGS_NAME)
                .addModifiers(Modifier.PUBLIC)
                .returns(wrapSet(STRING.className))
                .addCode(returnWrap(VARIABLE_ARGUMENTS))
                .build();
    }

    @NotNull
    private static MethodSpec validate() {
        return MethodSpec
                .methodBuilder(METHOD_VALIDATE_NAME)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .beginControlFlow("if (!$N.isEmpty())", VALIDATION_ERRORS_NAME)
                .addStatement("throw new $T($N)", VALIDATION_VIOLATION_EXCEPTION.className, VALIDATION_ERRORS_NAME)
                .endControlFlow()
                .build();
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {
        var mutation = processedSchema.getMutationType();
        if (mutation != null && mutation.isGenerated()) {
            writeToFile(generate(mutation), path, packagePath, getDefaultSaveDirectoryName());
        }
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public String getFileNameSuffix() {
        return FILE_NAME_SUFFIX;
    }
}
