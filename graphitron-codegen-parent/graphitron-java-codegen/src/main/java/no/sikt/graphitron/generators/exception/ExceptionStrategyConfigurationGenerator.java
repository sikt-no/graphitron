package no.sikt.graphitron.generators.exception;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.SchemaDefinition;
import no.sikt.graphitron.generators.abstractions.AbstractSchemaClassGenerator;
import no.sikt.graphitron.generators.abstractions.MethodGenerator;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.*;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.*;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.ErrorHandlerType.DATABASE;
import static no.sikt.graphitron.configuration.GeneratorConfig.getRecordValidation;
import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.setValue;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asGetMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapSet;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapStringMap;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphql.naming.GraphQLReservedName.ERROR_FIELD;

public class ExceptionStrategyConfigurationGenerator extends AbstractSchemaClassGenerator<SchemaDefinition> {
    private static final String
            PAYLOAD_NAME = "payload",
            PAYLOAD_FOR_FIELD_NAME = PAYLOAD_NAME + "ForField",
            EXCEPTION_FIELD = "fieldsForException";
    private static final ParameterizedTypeName FIELDS_FOR_EXCEPTIONS_TYPE =
            ParameterizedTypeName.get(MAP.className, ParameterizedTypeName.get(CLASS.className,
                    WildcardTypeName.subtypeOf(THROWABLE.className)), wrapSet(STRING.className));
    private static final TypeName PAYLOAD_FOR_FIELDS_TYPE = wrapStringMap(PAYLOAD_CREATOR.className);
    private final Map<ClassName, Set<String>> seenFieldsForException;

    public ExceptionStrategyConfigurationGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
        seenFieldsForException = new HashMap<>();
    }

    @Override
    public TypeSpec generate(SchemaDefinition schemaDefinition) {
        return getSpec("GeneratedExceptionStrategyConfiguration", List.of())
                .addMethod(createConstructor(schemaDefinition))
                .build();
    }

    private MethodSpec createConstructor(SchemaDefinition schemaDefinition) {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("$N = new $T<>()", EXCEPTION_FIELD, HASH_MAP.className)
                .addStatement("$N = new $T<>()", PAYLOAD_FOR_FIELD_NAME, HASH_MAP.className)
                .addCode(createConstructorContentForFields(schemaDefinition))
                .build();
    }

    private CodeBlock createConstructorContentForFields(SchemaDefinition schemaDefinition) {
        var queryType = schemaDefinition.getQuery() != null ? processedSchema.getObject(schemaDefinition.getQuery()) : null;
        var mutationType = schemaDefinition.getMutation() != null ? processedSchema.getObject(schemaDefinition.getMutation()) : null;

        return Stream
                .concat(queryType != null ? queryType.getFields().stream() : Stream.of(), mutationType != null ? mutationType.getFields().stream() : Stream.of())
                .sorted(Comparator.comparing(ObjectField::getName))
                .map(field -> {
                    var ctx = new InputParser(field, processedSchema);
                    var payloadBlockBuilder = CodeBlock.builder();

                    if (ctx.getValidationErrorException().isPresent()) {
                        payloadBlockBuilder
                                .add(createFieldsForExceptionBlock(field, VALIDATION_VIOLATION_EXCEPTION.className))
                                .add(createFieldsForExceptionBlock(field, ILLEGAL_ARGUMENT_EXCEPTION.className));
                    }

                    for (var errorField : ctx.getAllErrors()) {
                        for (var exc : processedSchema.getExceptionDefinitions(errorField.getTypeName())) {

                            exc.getExceptionToErrorMappings().forEach(mapping -> {
                                try {
                                    payloadBlockBuilder.add(createFieldsForExceptionBlock(field,
                                            mapping.getHandler() == DATABASE
                                                    ? DATA_ACCESS_EXCEPTION.className
                                                    : ClassName.get(Class.forName(mapping.getExceptionClassName()))));
                                } catch (ClassNotFoundException e) {
                                    throw new IllegalArgumentException("Unable to find exception className: " + mapping.getExceptionClassName() +
                                            ", declared for operation: " + field.getName(), e);
                                }
                            });
                        }
                    }

                    if (payloadBlockBuilder.isEmpty()) {
                        return CodeBlock.empty();
                    }

                    return payloadBlockBuilder
                            .add(createPayloadForFieldBlock(field, ctx))
                            .add("\n")
                            .build();
                })
                .collect(CodeBlock.joining());
    }

    private CodeBlock createFieldsForExceptionBlock(ObjectField field, ClassName exceptionClassName) {
        CodeBlock.Builder codeBlock = CodeBlock.builder();

        if (seenFieldsForException.containsKey(exceptionClassName)) {
            if (!seenFieldsForException.get(exceptionClassName).contains(field.getName())) {
                seenFieldsForException.get(exceptionClassName).add(field.getName());
                codeBlock.addStatement("$N.get($T.class).add($S)", EXCEPTION_FIELD, exceptionClassName, field.getName());
            }
        } else {
            HashSet<String> seenFields = new HashSet<>();
            seenFields.add(field.getName());
            seenFieldsForException.put(exceptionClassName, seenFields);
            codeBlock.addStatement("$N.computeIfAbsent($T.class, k -> new $T<>()).add($S)", EXCEPTION_FIELD, exceptionClassName, HashSet.class, field.getName());
        }

        return codeBlock.build();
    }

    private CodeBlock createPayloadForFieldBlock(ObjectField field, InputParser ctx) {
        var errorBlocks = ctx
                .getAllErrors()
                .stream()
                .map(errorField ->
                        setValue(
                                PAYLOAD_NAME,
                                errorField.getMappingFromSchemaName(),
                                CodeBlock.of(
                                        "($T) $N",
                                        ParameterizedTypeName.get(LIST.className, processedSchema.getErrorTypeDefinition(errorField.getTypeName()).getGraphClassName()),
                                        ERROR_FIELD.getName()
                                )
                        )
                )
                .toList();
        return CodeBlock
                .builder()
                .add("$N.put($S, ", PAYLOAD_FOR_FIELD_NAME, field.getName())
                .beginControlFlow("$L ->", ERROR_FIELD.getName())
                .declareNew(PAYLOAD_NAME, processedSchema.getObject(field.getTypeName()).getGraphClassName())
                .addAll(errorBlocks)
                .add(returnWrap(PAYLOAD_NAME))
                .endControlFlow(")")
                .build();
    }

    @Override
    public List<TypeSpec> generateAll() {
        if (recordValidationEnabled() && getRecordValidation().getSchemaErrorType().isPresent() || schemaContainsExceptionToErrorMappings()) {
            var generated = Optional
                    .ofNullable(processedSchema.getSchemaType())
                    .map(this::generate)
                    .filter(it -> !it.methodSpecs().isEmpty());
            if (generated.isPresent()) {
                return List.of(generated.get());
            }
        }
        return List.of();
    }

    private boolean schemaContainsExceptionToErrorMappings() {
        return processedSchema.getExceptions().values().stream().anyMatch(it -> !it.getExceptionToErrorMappings().isEmpty());
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
    public TypeSpec.Builder getSpec(String className, List<? extends MethodGenerator> generators) {
        return TypeSpec.classBuilder(className)
                .addSuperinterface(EXCEPTION_STRATEGY_CONFIGURATION.className)
                .addModifiers(Modifier.PUBLIC)
                .addField(FieldSpec.builder(FIELDS_FOR_EXCEPTIONS_TYPE, EXCEPTION_FIELD, Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(PAYLOAD_FOR_FIELDS_TYPE, PAYLOAD_FOR_FIELD_NAME, Modifier.PRIVATE, Modifier.FINAL).build())
                .addMethod(createGetFieldsForExceptionsMethod())
                .addMethod(createGetPayloadForFieldsMethod());
    }

    private static MethodSpec createGetFieldsForExceptionsMethod() {
        return MethodSpec
                .methodBuilder(asGetMethodName(EXCEPTION_FIELD))
                .returns(FIELDS_FOR_EXCEPTIONS_TYPE)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(OVERRIDE.className)
                .addCode(returnWrap(EXCEPTION_FIELD))
                .build();
    }

    private static MethodSpec createGetPayloadForFieldsMethod() {
        return MethodSpec
                .methodBuilder(asGetMethodName(PAYLOAD_FOR_FIELD_NAME))
                .returns(PAYLOAD_FOR_FIELDS_TYPE)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(OVERRIDE.className)
                .addCode(returnWrap(PAYLOAD_FOR_FIELD_NAME))
                .build();
    }
}
