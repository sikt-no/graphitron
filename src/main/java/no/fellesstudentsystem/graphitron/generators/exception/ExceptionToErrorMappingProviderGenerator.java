package no.fellesstudentsystem.graphitron.generators.exception;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.configuration.ErrorHandlerType;
import no.fellesstudentsystem.graphitron.configuration.ExceptionToErrorMapping;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.objects.ExceptionDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.MethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.*;

import static no.fellesstudentsystem.graphitron.configuration.ErrorHandlerType.DATABASE;
import static no.fellesstudentsystem.graphitron.configuration.ErrorHandlerType.GENERIC;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapList;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asGetMethodName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asListedName;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

public class ExceptionToErrorMappingProviderGenerator implements ClassGenerator<ObjectDefinition> {
    private static final String DATA_ACCESS_MAPPINGS_FOR_MUTATION_FIELD_NAME = "dataAccessMappingsForMutation";
    private static final String GENERIC_MAPPINGS_FOR_MUTATION_FIELD_NAME = "genericMappingsForMutation";

    private static final TypeName DATA_ACCESS_ERROR_MAPPINGS_TYPE = ParameterizedTypeName.get(MAP.className, STRING.className, wrapList(DATA_ACCESS_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className));
    private static final TypeName GENERIC_ERROR_MAPPINGS_TYPE = ParameterizedTypeName.get(MAP.className, STRING.className, wrapList(GENERIC_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className));
    private static final String MAPPING_VARIABLE_PREFIX = "m";
    private final ProcessedSchema processedSchema;

    public ExceptionToErrorMappingProviderGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec("GeneratedExceptionToErrorMappingProvider", List.of())
                .addAnnotation(SINGLETON.className)
                .addMethod(createConstructor(target))
                .build();
    }

    private MethodSpec createConstructor(ObjectDefinition target) {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addCode(CodeBlock.builder()
                        .addStatement("$N = new $T<>()", DATA_ACCESS_MAPPINGS_FOR_MUTATION_FIELD_NAME, HASH_MAP.className)
                        .addStatement("$N = new $T<>()", GENERIC_MAPPINGS_FOR_MUTATION_FIELD_NAME, HASH_MAP.className)
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
        if (processedSchema.getExceptions().entrySet().stream().anyMatch(it -> !it.getValue().getExceptionToErrorMappings().isEmpty())) {
            Optional.ofNullable(processedSchema.getMutationType())
                    .map(this::generate)
                    .filter(it -> !it.methodSpecs.isEmpty())
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
                .addSuperinterface(EXCEPTION_TO_ERROR_MAPPING_PROVIDER.className)
                .addModifiers(Modifier.PUBLIC)
                .addField(FieldSpec.builder(DATA_ACCESS_ERROR_MAPPINGS_TYPE, DATA_ACCESS_MAPPINGS_FOR_MUTATION_FIELD_NAME, Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(GENERIC_ERROR_MAPPINGS_TYPE, GENERIC_MAPPINGS_FOR_MUTATION_FIELD_NAME, Modifier.PRIVATE, Modifier.FINAL).build())
                .addMethod(createGetDataAccessMappingsForMutationMethod())
                .addMethod(createGetGenericMappingsForMutationMethod());
    }

    private static MethodSpec createGetDataAccessMappingsForMutationMethod() {
        return MethodSpec
                .methodBuilder(asGetMethodName(DATA_ACCESS_MAPPINGS_FOR_MUTATION_FIELD_NAME))
                .returns(DATA_ACCESS_ERROR_MAPPINGS_TYPE)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(OVERRIDE.className)
                .addCode(returnWrap(DATA_ACCESS_MAPPINGS_FOR_MUTATION_FIELD_NAME))
                .build();
    }

    private static MethodSpec createGetGenericMappingsForMutationMethod() {
        return MethodSpec
                .methodBuilder(asGetMethodName(GENERIC_MAPPINGS_FOR_MUTATION_FIELD_NAME))
                .returns(GENERIC_ERROR_MAPPINGS_TYPE)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(OVERRIDE.className)
                .addCode(returnWrap(GENERIC_MAPPINGS_FOR_MUTATION_FIELD_NAME))
                .build();
    }

    private class MutationProcessor {
        private static final String MSG_VARIABLE_NAME = "msg";
        private final UpdateContext ctx;
        private final String mutationName;
        private boolean databaseMappingIsCreatedForMutation = false;
        private boolean genericMappingIsCreatedForMutation = false;

        MutationProcessor(ObjectField mutation) {
            this.ctx = new UpdateContext(mutation, processedSchema);
            this.mutationName = mutation.getName();
        }

        public CodeBlock process(Map<ExceptionToErrorMapping, Integer> exceptionMappingsToErrorMappingVariables, Map<Integer, CodeBlock> mappingVariablesToBlocks) {
            var codeBuilder = CodeBlock.builder();
            var databaseListName = asListedName(mutationName + DATABASE.toCamelCaseString());
            var genericListName = asListedName(mutationName + GENERIC.toCamelCaseString());

            for (var errorField : ctx.getAllErrors()) {
                List<ExceptionDefinition> exceptionDefinitions = ctx.getProcessedSchema().getExceptionDefinitions(errorField.getTypeName());

                var databaseMappingVariablesBlock = createMappingVariablesBlock(exceptionDefinitions, DATABASE, exceptionMappingsToErrorMappingVariables, mappingVariablesToBlocks);
                var genericMappingVariablesBlock = createMappingVariablesBlock(exceptionDefinitions, GENERIC, exceptionMappingsToErrorMappingVariables, mappingVariablesToBlocks);

                if (!databaseMappingVariablesBlock.isEmpty()) {
                    codeBuilder.add("\n");
                    codeBuilder.add(declare(databaseListName, listOf(databaseMappingVariablesBlock)));
                    databaseMappingIsCreatedForMutation = true;
                }

                if (!genericMappingVariablesBlock.isEmpty()) {
                    codeBuilder.add("\n");
                    codeBuilder.add(declare(genericListName, listOf(genericMappingVariablesBlock)));
                    genericMappingIsCreatedForMutation = true;
                }
            }

            if (databaseMappingIsCreatedForMutation) {
                codeBuilder.addStatement("$N.put($S, $N)", DATA_ACCESS_MAPPINGS_FOR_MUTATION_FIELD_NAME, mutationName, databaseListName);
            }

            if (genericMappingIsCreatedForMutation) {
                codeBuilder.addStatement("$N.put($S, $N)", GENERIC_MAPPINGS_FOR_MUTATION_FIELD_NAME, mutationName, genericListName);
            }

            return codeBuilder.build();
        }

        private CodeBlock createMappingVariablesBlock(List<ExceptionDefinition> exceptionDefinitions, ErrorHandlerType handlerType, Map<ExceptionToErrorMapping, Integer> exceptionMappingsToErrorMappingVariables, Map<Integer, CodeBlock> mappingVariablesToBlocks) {
            return exceptionDefinitions.stream()
                    .map(ExceptionDefinition::getExceptionToErrorMappings)
                    .flatMap(Collection::stream)
                    .filter(it -> it.getHandler() == handlerType)
                    .map(it -> processErrorMapping(it, exceptionMappingsToErrorMappingVariables, mappingVariablesToBlocks))
                    .collect(CodeBlock.joining(", "));
        }

        private CodeBlock processErrorMapping(ExceptionToErrorMapping errorMapping, Map<ExceptionToErrorMapping, Integer> exceptionMappingsToErrorMappingVariables, Map<Integer, CodeBlock> mappingVariablesToBlocks) {
            var codeBuilder = CodeBlock.builder();
            var variableNumber = exceptionMappingsToErrorMappingVariables.get(errorMapping);

            if (variableNumber == null) {
                int numberOfMappingVariablesFound = exceptionMappingsToErrorMappingVariables.size() + 1;
                exceptionMappingsToErrorMappingVariables.put(errorMapping, numberOfMappingVariablesFound);

                switch (errorMapping.getHandler()) {
                    case DATABASE:
                        mappingVariablesToBlocks.put(numberOfMappingVariablesFound,
                                createExceptionToErrorMappingCodeBlock(
                                        DATA_ACCESS_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className,
                                        DATA_ACCESS_EXCEPTION_MAPPING_CONTENT.className,
                                        errorMapping.getDatabaseErrorCode(),
                                        errorMapping));
                        break;
                    case GENERIC:
                        mappingVariablesToBlocks.put(numberOfMappingVariablesFound,
                                createExceptionToErrorMappingCodeBlock(
                                        GENERIC_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className,
                                        GENERIC_EXCEPTION_MAPPING_CONTENT.className,
                                        errorMapping.getExceptionClassName(),
                                        errorMapping));
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown handler: " + errorMapping.getHandler());
                }
                variableNumber = numberOfMappingVariablesFound;
            }

            codeBuilder.add("$L$L", MAPPING_VARIABLE_PREFIX, variableNumber);
            return codeBuilder.build();
        }

        private CodeBlock createExceptionToErrorMappingCodeBlock(ClassName contentToErrorMappingClassName, ClassName contentClassName, String constructorArg1, ExceptionToErrorMapping exceptionToErrorMapping) {
            return CodeBlock.builder()
                    .add("new $T(\n", contentToErrorMappingClassName)
                    .indent()
                    .add("new $T($S, $S),\n",
                            contentClassName,
                            constructorArg1,
                            exceptionToErrorMapping.getExceptionMessageContains())
                    .add("(path, $L) -> new $T(path, $L))",
                            MSG_VARIABLE_NAME,
                            processedSchema.getObject(exceptionToErrorMapping.getErrorTypeName()).getGraphClassName(),
                            exceptionToErrorMapping.getErrorDescription().map(it -> CodeBlock.of("$S", it)).orElse(CodeBlock.of(MSG_VARIABLE_NAME)))
                    .unindent()
                    .build();
        }
    }
}
