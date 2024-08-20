package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractClassGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.MethodGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

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
    private final List<MethodGenerator<? extends GenerationTarget>> generators = new ArrayList<>();

    public TransformerClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
        generators.add(new TransformerListMethodGenerator(processedSchema));
        generators.add(new TransformerMethodGenerator(processedSchema));
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec("", generators).build();
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<MethodGenerator<? extends GenerationTarget>> generators) {
        return super
                .getSpec("", generators)
                .superclass(ABSTRACT_TRANSFORMER.className)
                .addMethod(constructor());
    }

    @NotNull
    private static MethodSpec constructor() {
        return MethodSpec
                .constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
                .addParameter(DSL_CONTEXT.className, CONTEXT_NAME)
                .addStatement("super($N, $N)", VARIABLE_ENV, CONTEXT_NAME)
                .build();
    }

    @Override
    public List<TypeSpec> generateTypeSpecs() {
        return List.of(generate(null));
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
