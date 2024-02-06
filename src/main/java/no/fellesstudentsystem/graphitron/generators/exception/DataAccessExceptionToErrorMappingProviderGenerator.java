package no.fellesstudentsystem.graphitron.generators.exception;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.configuration.ExceptionToErrorMapping;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.MethodGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.declareArrayList;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapList;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asGetMethodName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asListedName;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

public class DataAccessExceptionToErrorMappingProviderGenerator implements ClassGenerator<ObjectDefinition> {
    private static final String MAPPINGS_FOR_MUTATION_FIELD_NAME = "mappingsForMutation";
    private static final TypeName ERROR_MAPPINGS_TYPE = ParameterizedTypeName.get(MAP.className, STRING.className, wrapList(DATA_ACCESS_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className));
    private final ProcessedSchema processedSchema;

    public DataAccessExceptionToErrorMappingProviderGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec("GeneratedDataAccessExceptionToErrorMappingProvider", List.of())
                .addMethod(createConstructor(target))
                .build();
    }

    private MethodSpec createConstructor(ObjectDefinition target) {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addCode(CodeBlock.builder()
                        .addStatement("$N = new $T<>()", MAPPINGS_FOR_MUTATION_FIELD_NAME, HASH_MAP.className)
                        .add(createConstructorContentForMutations(target))
                        .build())
                .build();
    }

    private CodeBlock createConstructorContentForMutations(ObjectDefinition mutationTypeDefinition) {
        var codeBuilder = CodeBlock.builder();
        var errorMappingsForMutationName = GeneratorConfig.getErrorMappingsForMutationName();


        errorMappingsForMutationName.forEach((key, value) -> {

        });

        mutationTypeDefinition.getFields().stream()
                .sorted(Comparator.comparing(ObjectField::getName))
                .forEach(mutation -> {
                    List<ExceptionToErrorMapping> errorMappings = errorMappingsForMutationName.getOrDefault(mutation.getName(), List.of());

                    for (int i = 0; i < errorMappings.size(); i++) {
                        ExceptionToErrorMapping exceptionToErrorMapping = errorMappings.get(i);

                        if (i == 0) {
                            codeBuilder.add("\n");
                            codeBuilder.add(declareArrayList(exceptionToErrorMapping.getMutationName(), DATA_ACCESS_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className));
                        }

                        var errorsListName = asListedName(exceptionToErrorMapping.getMutationName());

                        codeBuilder.addStatement(
                                CodeBlock.builder()
                                        .add("$N.add(\n", errorsListName)
                                        .indent()
                                        .add("new $T(\n", DATA_ACCESS_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className)
                                        .indent()
                                        .add("new $T($S, $S),\n",
                                                DATA_ACCESS_EXCEPTION_MAPPING_CONTENT.className,
                                                exceptionToErrorMapping.getDatabaseErrorCode(),
                                                exceptionToErrorMapping.getExceptionMessageContains())
                                        .add("path -> new $T(path, $L)))",
                                                processedSchema.getObject(exceptionToErrorMapping.getErrorTypeName()).getGraphClassName(), exceptionToErrorMapping.getErrorDescription().map(it -> CodeBlock.of("$S", it)).orElse(CodeBlock.of("null")))
                                        .unindent().unindent()
                                        .build());

                        if (i == errorMappings.size()-1) {
                            codeBuilder.addStatement("$N.put($S, $N)", MAPPINGS_FOR_MUTATION_FIELD_NAME, mutation.getName(), errorsListName);
                        }
                    }

                });
        return codeBuilder.build();
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {

        if (!GeneratorConfig.getErrorMappingsForMutationName().isEmpty()) {
            Optional.ofNullable(processedSchema.getMutationType())
                    .map(this::generate)
                    .ifPresent(spec -> writeToFile(spec, path, packagePath, getDefaultSaveDirectoryName()));
        }
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath) {
        writeToFile(generatedClass, path, packagePath, getDefaultSaveDirectoryName());
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath, String directoryOverride) {
        var file = JavaFile
                .builder(packagePath + "." + directoryOverride, generatedClass)
                .indent("    ")
                .build();
        try {
            file.writeTo(new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return "exception";
    }

    @Override
    public String getFileNameSuffix() {
        return "";
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<MethodGenerator<? extends GenerationTarget>> generators) {
        return TypeSpec.classBuilder(className)
                .addSuperinterface(DATA_ACCESS_EXCEPTION_TO_ERROR_MAPPING_PROVIDER.className)
                .addModifiers(Modifier.PUBLIC)
                .addField(FieldSpec.builder(ERROR_MAPPINGS_TYPE, MAPPINGS_FOR_MUTATION_FIELD_NAME, Modifier.PRIVATE, Modifier.FINAL).build())
                .addMethod(createGetMappingsForMutationMethod());
    }

    private static MethodSpec createGetMappingsForMutationMethod() {
        return MethodSpec
                .methodBuilder(asGetMethodName(MAPPINGS_FOR_MUTATION_FIELD_NAME))
                .returns(ERROR_MAPPINGS_TYPE)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(OVERRIDE.className)
                .addStatement("return $N", MAPPINGS_FOR_MUTATION_FIELD_NAME)
                .build();
    }
}
