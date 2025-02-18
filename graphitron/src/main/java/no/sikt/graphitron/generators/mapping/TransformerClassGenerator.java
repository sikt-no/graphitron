package no.sikt.graphitron.generators.mapping;

import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.AbstractSchemaClassGenerator;
import no.sikt.graphitron.generators.abstractions.MethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.ABSTRACT_TRANSFORMER;
import static no.sikt.graphitron.mappings.JavaPoetClassName.DATA_FETCHING_ENVIRONMENT;
import static org.apache.commons.lang3.StringUtils.capitalize;

public class TransformerClassGenerator extends AbstractSchemaClassGenerator<ObjectDefinition> {
    public static final String
            FILE_NAME_SUFFIX = "RecordTransformer",
            METHOD_VALIDATE_NAME = "validate",
            METHOD_CONTEXT_NAME = "get" + capitalize(CONTEXT_NAME),
            METHOD_ENV_NAME = "get" + capitalize(VARIABLE_ENV),
            METHOD_SELECT_NAME = "get" + capitalize(VARIABLE_SELECT),
            DEFAULT_SAVE_DIRECTORY_NAME = "transform";
    private final List<MethodGenerator> generators = new ArrayList<>();

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
    public TypeSpec.Builder getSpec(String className, List<? extends MethodGenerator> generators) {
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
                .addStatement("super($N)", VARIABLE_ENV)
                .build();
    }

    @Override
    public List<TypeSpec> generateAll() {
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
