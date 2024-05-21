package no.fellesstudentsystem.graphitron.generators.exception;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.configuration.ExceptionToErrorMapping;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.objects.ExceptionDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.MethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jooq.exception.DataAccessException;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.*;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapList;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asGetMethodName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asListedName;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

public class DataAccessExceptionToErrorMappingProviderGenerator implements ClassGenerator<ObjectDefinition> {
    private static final String MAPPINGS_FOR_MUTATION_FIELD_NAME = "mappingsForMutation";
    private static final TypeName ERROR_MAPPINGS_TYPE = ParameterizedTypeName.get(MAP.className, STRING.className, wrapList(DATA_ACCESS_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className));
    private static final String MAPPING_VARIABLE_PREFIX = "m";
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
        Map<ExceptionToErrorMapping, Integer> exceptionMappingsToErrorMappingVariables = new HashMap<>();
        Map<Integer, CodeBlock> mappingVariablesToBlocks = new HashMap<>();

        var mutationErrorListsCodeblock = mutationTypeDefinition.getFields().stream()
                .sorted(Comparator.comparing(ObjectField::getName))
                .map(mutation -> new MutationProcessor(mutation).process(exceptionMappingsToErrorMappingVariables, mappingVariablesToBlocks))
                .collect(CodeBlock.joining(""));

        var codeBuilder = CodeBlock.builder();
        mappingVariablesToBlocks.forEach((key, value) -> codeBuilder.add(declare(MAPPING_VARIABLE_PREFIX + key, value)));

        codeBuilder.add(mutationErrorListsCodeblock);
        return codeBuilder.build();
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {

        if (!processedSchema.getExceptions(DataAccessException.class.getName()).isEmpty()) {
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
                .addCode(returnWrap(MAPPINGS_FOR_MUTATION_FIELD_NAME))
                .build();
    }

    private class MutationProcessor {
        private final UpdateContext ctx;
        private final String mutationName;
        private final String errorsListName;
        private boolean mappingIsCreatedForMutation = false;

        MutationProcessor(ObjectField mutation) {
            this.ctx = new UpdateContext(mutation, processedSchema);
            this.mutationName = mutation.getName();
            this.errorsListName = asListedName(mutationName);
        }

        public CodeBlock process(Map<ExceptionToErrorMapping, Integer> exceptionMappingsToErrorMappingVariables, Map<Integer, CodeBlock> mappingVariablesToBlocks) {
            var codeBuilder = CodeBlock.builder();

            for (var errorField : ctx.getAllErrors()) {
                List<ExceptionDefinition> exceptionDefinitions = ctx.getProcessedSchema().getExceptionDefinitions(errorField.getTypeName());

                var mappingVariablesBlock = exceptionDefinitions.stream()
                        .map(ExceptionDefinition::getExceptionToErrorMappings)
                        .flatMap(Collection::stream)
                        .map(it -> processErrorMapping(it, exceptionMappingsToErrorMappingVariables, mappingVariablesToBlocks))
                        .collect(CodeBlock.joining(", "));

                if (!mappingVariablesBlock.isEmpty()) {
                    codeBuilder.add("\n");
                    codeBuilder.add(declare(asListedName(mutationName), listOf(mappingVariablesBlock)));
                    mappingIsCreatedForMutation = true;
                }
            }

            if (mappingIsCreatedForMutation) {
                codeBuilder.addStatement("$N.put($S, $N)", MAPPINGS_FOR_MUTATION_FIELD_NAME, mutationName, errorsListName);
            }

            return codeBuilder.build();
        }

        private CodeBlock processErrorMapping(ExceptionToErrorMapping errorMapping, Map<ExceptionToErrorMapping, Integer> exceptionMappingsToErrorMappingVariables, Map<Integer, CodeBlock> mappingVariablesToBlocks) {
            var codeBuilder = CodeBlock.builder();
            var variableNumber = exceptionMappingsToErrorMappingVariables.get(errorMapping);

            if (variableNumber == null) {
                int numberOfMappingVariablesFound = exceptionMappingsToErrorMappingVariables.size() + 1;
                exceptionMappingsToErrorMappingVariables.put(errorMapping, numberOfMappingVariablesFound);
                mappingVariablesToBlocks.put(numberOfMappingVariablesFound, createErrorMappingCodeBlock(errorMapping));
                variableNumber = numberOfMappingVariablesFound;
            }

            codeBuilder.add("$L$L", MAPPING_VARIABLE_PREFIX, variableNumber);
            return codeBuilder.build();
        }

        private  CodeBlock createErrorMappingCodeBlock(ExceptionToErrorMapping exceptionToErrorMapping) {
            return CodeBlock.builder()
                    .add("new $T(\n", DATA_ACCESS_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className)
                    .indent()
                    .add("new $T($S, $S),\n",
                            DATA_ACCESS_EXCEPTION_MAPPING_CONTENT.className,
                            exceptionToErrorMapping.getDatabaseErrorCode(),
                            exceptionToErrorMapping.getExceptionMessageContains())
                    .add("path -> new $T(path, $L))",
                            processedSchema.getObject(exceptionToErrorMapping.getErrorTypeName()).getGraphClassName(), exceptionToErrorMapping.getErrorDescription().map(it -> CodeBlock.of("$S", it)).orElse(CodeBlock.of("null")))
                    .unindent()
                    .build();
        }
    }
}
