package no.sikt.graphitron.generators.mapping;

import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.generators.abstractions.MethodGenerator;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.sikt.graphitron.mappings.JavaPoetClassName.ABSTRACT_TRANSFORMER;
import static no.sikt.graphitron.mappings.JavaPoetClassName.DATA_FETCHING_ENVIRONMENT;

public class TransformerClassGenerator extends AbstractClassGenerator {
    public static final String FILE_NAME_SUFFIX = "RecordTransformer", DEFAULT_SAVE_DIRECTORY_NAME = "transform";
    private final List<MethodGenerator> generators;

    public TransformerClassGenerator(ProcessedSchema processedSchema) {
        generators = List.of(
                new TransformerListMethodGenerator(processedSchema),
                new TransformerMethodGenerator(processedSchema)
        );
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
        return List.of(getSpec("", generators).build());
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
