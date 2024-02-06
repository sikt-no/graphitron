package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractClassGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.MethodGenerator;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.util.List;

import static no.fellesstudentsystem.graphitron.generators.abstractions.AbstractMethodGenerator.ENV_NAME;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapSet;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.CONTEXT_NAME;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.VALIDATION_ERRORS_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

public class TransformerClassGenerator extends AbstractClassGenerator<ObjectDefinition> {
    public static final String
            FILE_NAME_SUFFIX = "InputTransformer",
            METHOD_VALIDATE_NAME = "validate",
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
                .addField(DATA_FETCHING_ENVIRONMENT.className, ENV_NAME, Modifier.PRIVATE, Modifier.FINAL)
                .addField(wrapSet(STRING.className), VariableNames.VARIABLE_ARGUMENTS, Modifier.PRIVATE, Modifier.FINAL)
                .addField(FieldSpec.builder(ParameterizedTypeName.get(HASH_SET.className, GRAPHQL_ERROR.className), VALIDATION_ERRORS_NAME, Modifier.PRIVATE, Modifier.FINAL).initializer("new $T<$T>()", HASH_SET.className, GRAPHQL_ERROR.className).build())
                .addMethod(getConstructor())
                .addMethod(getValidate());
    }

    @NotNull
    private static MethodSpec getConstructor() {
        return MethodSpec
                .constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, ENV_NAME)
                .addParameter(DSL_CONTEXT.className, CONTEXT_NAME)
                .addStatement("this.$N = $N", ENV_NAME, ENV_NAME)
                .addStatement("this.$N = $N", CONTEXT_NAME, CONTEXT_NAME)
                .addStatement("$N = $T.flattenArgumentKeys($N.getArguments())", VariableNames.VARIABLE_ARGUMENTS, ARGUMENTS.className, ENV_NAME)
                .build();
    }

    @NotNull
    private static MethodSpec getValidate() {
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
