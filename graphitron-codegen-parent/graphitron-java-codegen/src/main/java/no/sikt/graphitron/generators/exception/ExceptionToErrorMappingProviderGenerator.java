package no.sikt.graphitron.generators.exception;

import no.sikt.graphitron.configuration.ErrorHandlerType;
import no.sikt.graphitron.configuration.ExceptionToErrorMapping;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ExceptionDefinition;
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
import static no.sikt.graphitron.configuration.ErrorHandlerType.GENERIC;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.listOf;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asGetMethodName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asListedName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapList;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

public class ExceptionToErrorMappingProviderGenerator extends AbstractSchemaClassGenerator<SchemaDefinition> {
    private static final String DATA_ACCESS_MAPPINGS_FOR_FIELD_NAME = "dataAccessMappingsForOperation";
    private static final String GENERIC_MAPPINGS_FOR_FIELD_NAME = "genericMappingsForOperation";

    private static final TypeName DATA_ACCESS_ERROR_MAPPINGS_TYPE = ParameterizedTypeName.get(MAP.className, STRING.className, wrapList(DATA_ACCESS_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className));
    private static final TypeName GENERIC_ERROR_MAPPINGS_TYPE = ParameterizedTypeName.get(MAP.className, STRING.className, wrapList(GENERIC_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className));
    private static final String MAPPING_VARIABLE_PREFIX = "m";
    private static final String SHARED_LIST_NAME_PREFIX = "shared";
    private static final String INIT_METHOD_PREFIX = "initMappings";
    private static final String MSG_VARIABLE_NAME = "msg";

    /*
     * Conservative upper-bound estimates (in bytes) of the bytecode each generated construct compiles to.
     * The JVM rejects methods above 65535 bytes of bytecode ("code too large"), so whenever the estimated
     * total exceeds the limit below, the initialization is split across several private methods instead of
     * being inlined in the constructor. The limit leaves a wide margin for estimation error.
     */
    private static final int MAPPING_DECLARATION_SIZE_ESTIMATE = 40;
    private static final int LIST_DECLARATION_SIZE_ESTIMATE = 16;
    private static final int LIST_REFERENCE_SIZE_ESTIMATE = 12;
    private static final int MAP_PUT_SIZE_ESTIMATE = 24;
    public static final int DEFAULT_MAX_METHOD_SIZE_ESTIMATE = 30_000;

    private final int maxMethodSizeEstimate;

    public ExceptionToErrorMappingProviderGenerator(ProcessedSchema processedSchema) {
        this(processedSchema, DEFAULT_MAX_METHOD_SIZE_ESTIMATE);
    }

    public ExceptionToErrorMappingProviderGenerator(ProcessedSchema processedSchema, int maxMethodSizeEstimate) {
        super(processedSchema);
        this.maxMethodSizeEstimate = maxMethodSizeEstimate;
    }

    @Override
    public TypeSpec generate(SchemaDefinition schemaDefinition) {
        var content = collectContent(schemaDefinition);
        var spec = getSpec("GeneratedExceptionToErrorMappingProvider", List.of());
        if (estimatedTotalSize(content) <= maxMethodSizeEstimate) {
            spec.addMethod(createInlineConstructor(content));
        } else {
            addChunkedContent(spec, content);
        }
        return spec.build();
    }

    /**
     * A deduplicated exception mapping, declared once and referenced by name in the operation lists.
     */
    private record MappingDeclaration(String name, ErrorHandlerType handler, CodeBlock initializer) {
    }

    /**
     * One mapping list and the operations it applies to. Operations with identical mapping lists share
     * a single group, so the list is only generated once.
     */
    private record MappingListGroup(String name, ErrorHandlerType handler, List<String> mappingNames, List<String> operationNames) {
    }

    private record ProviderContent(List<MappingDeclaration> mappings, List<MappingListGroup> listGroups) {
    }

    private record MappingListKey(ErrorHandlerType handler, List<String> mappingNames) {
    }

    private ProviderContent collectContent(SchemaDefinition schemaDefinition) {
        var mappingVariableNames = new HashMap<ExceptionToErrorMapping, String>();
        var mappingDeclarations = new ArrayList<MappingDeclaration>();
        var operationNamesForList = new LinkedHashMap<MappingListKey, List<String>>();

        var queryType = schemaDefinition.getQuery() != null ? processedSchema.getObject(schemaDefinition.getQuery()) : null;
        var mutationType = schemaDefinition.getMutation() != null ? processedSchema.getObject(schemaDefinition.getMutation()) : null;
        var operations = Stream
                .concat(queryType != null ? queryType.getFields().stream() : Stream.of(), mutationType != null ? mutationType.getFields().stream() : Stream.of())
                .sorted(Comparator.comparing(ObjectField::getName))
                .toList();

        for (var operation : operations) {
            var databaseMappingNames = new LinkedHashSet<String>();
            var genericMappingNames = new LinkedHashSet<String>();
            for (var errorField : new InputParser(operation, processedSchema).getAllErrors()) {
                var exceptionDefinitions = processedSchema.getExceptionDefinitions(errorField.getTypeName());
                databaseMappingNames.addAll(mappingNamesFor(exceptionDefinitions, DATABASE, mappingVariableNames, mappingDeclarations));
                genericMappingNames.addAll(mappingNamesFor(exceptionDefinitions, GENERIC, mappingVariableNames, mappingDeclarations));
            }

            if (!databaseMappingNames.isEmpty()) {
                operationNamesForList
                        .computeIfAbsent(new MappingListKey(DATABASE, List.copyOf(databaseMappingNames)), key -> new ArrayList<>())
                        .add(operation.getName());
            }
            if (!genericMappingNames.isEmpty()) {
                operationNamesForList
                        .computeIfAbsent(new MappingListKey(GENERIC, List.copyOf(genericMappingNames)), key -> new ArrayList<>())
                        .add(operation.getName());
            }
        }

        var listGroups = new ArrayList<MappingListGroup>();
        var sharedListCounters = new EnumMap<ErrorHandlerType, Integer>(ErrorHandlerType.class);
        operationNamesForList.forEach((key, operationNames) -> {
            var name = operationNames.size() == 1
                    ? asListedName(operationNames.get(0) + key.handler().toCamelCaseString())
                    : asListedName(SHARED_LIST_NAME_PREFIX + key.handler().toCamelCaseString()) + sharedListCounters.merge(key.handler(), 1, Integer::sum);
            listGroups.add(new MappingListGroup(name, key.handler(), key.mappingNames(), operationNames));
        });
        return new ProviderContent(mappingDeclarations, listGroups);
    }

    private List<String> mappingNamesFor(
            List<ExceptionDefinition> exceptionDefinitions,
            ErrorHandlerType handlerType,
            Map<ExceptionToErrorMapping, String> mappingVariableNames,
            List<MappingDeclaration> mappingDeclarations
    ) {
        return exceptionDefinitions.stream()
                .map(ExceptionDefinition::getExceptionToErrorMappings)
                .flatMap(Collection::stream)
                .filter(it -> it.getHandler() == handlerType)
                .map(it -> {
                    var existingName = mappingVariableNames.get(it);
                    if (existingName != null) {
                        return existingName;
                    }
                    var name = MAPPING_VARIABLE_PREFIX + (mappingVariableNames.size() + 1);
                    mappingVariableNames.put(it, name);
                    mappingDeclarations.add(new MappingDeclaration(name, handlerType, createExceptionToErrorMappingCodeBlock(it)));
                    return name;
                })
                .toList();
    }

    private MethodSpec createInlineConstructor(ProviderContent content) {
        var codeBuilder = createFieldInitializationCode();
        content.mappings().forEach(mapping -> codeBuilder.declare(mapping.name(), mapping.initializer()));
        content.listGroups().forEach(group -> codeBuilder.add(createListGroupBlock(group)));
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addCode(codeBuilder.build())
                .build();
    }

    private void addChunkedContent(TypeSpec.Builder spec, ProviderContent content) {
        content.mappings().forEach(mapping -> spec.addField(
                FieldSpec.builder(mappingType(mapping.handler()), mapping.name(), Modifier.PRIVATE).build()));

        var initMethods = new ArrayList<MethodSpec>();
        var chunkBuilder = CodeBlock.builder();
        var chunkSizeEstimate = 0;
        for (var unit : createCodeUnits(content)) {
            if (chunkSizeEstimate > 0 && chunkSizeEstimate + unit.sizeEstimate() > maxMethodSizeEstimate) {
                initMethods.add(createInitMethod(initMethods.size() + 1, chunkBuilder.build()));
                chunkBuilder = CodeBlock.builder();
                chunkSizeEstimate = 0;
            }
            chunkBuilder.add(unit.code());
            chunkSizeEstimate += unit.sizeEstimate();
        }
        if (chunkSizeEstimate > 0) {
            initMethods.add(createInitMethod(initMethods.size() + 1, chunkBuilder.build()));
        }

        var constructorBuilder = createFieldInitializationCode();
        initMethods.forEach(method -> constructorBuilder.addStatement("$N()", method.name()));
        spec.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addCode(constructorBuilder.build())
                .build());
        initMethods.forEach(spec::addMethod);
    }

    private record CodeUnit(int sizeEstimate, CodeBlock code) {
    }

    private List<CodeUnit> createCodeUnits(ProviderContent content) {
        var units = new ArrayList<CodeUnit>();
        content.mappings().forEach(mapping -> units.add(new CodeUnit(
                MAPPING_DECLARATION_SIZE_ESTIMATE,
                CodeBlock.builder().addStatement("$N = $L", mapping.name(), mapping.initializer()).build())));
        content.listGroups().forEach(group -> units.add(new CodeUnit(estimatedSize(group), createListGroupBlock(group))));
        return units;
    }

    private static MethodSpec createInitMethod(int methodNumber, CodeBlock code) {
        return MethodSpec.methodBuilder(INIT_METHOD_PREFIX + methodNumber)
                .addModifiers(Modifier.PRIVATE)
                .addCode(code)
                .build();
    }

    private static CodeBlock.Builder createFieldInitializationCode() {
        return CodeBlock.builder()
                .addStatement("$N = new $T<>()", DATA_ACCESS_MAPPINGS_FOR_FIELD_NAME, HASH_MAP.className)
                .addStatement("$N = new $T<>()", GENERIC_MAPPINGS_FOR_FIELD_NAME, HASH_MAP.className);
    }

    private static CodeBlock createListGroupBlock(MappingListGroup group) {
        var codeBuilder = CodeBlock.builder()
                .add("\n")
                .declare(group.name(), listOf(group.mappingNames().stream().map(name -> CodeBlock.of("$N", name)).collect(CodeBlock.joining(", "))));
        var mapFieldName = group.handler() == DATABASE ? DATA_ACCESS_MAPPINGS_FOR_FIELD_NAME : GENERIC_MAPPINGS_FOR_FIELD_NAME;
        group.operationNames().forEach(operationName -> codeBuilder.addStatement("$N.put($S, $N)", mapFieldName, operationName, group.name()));
        return codeBuilder.build();
    }

    private static TypeName mappingType(ErrorHandlerType handler) {
        return handler == DATABASE ? DATA_ACCESS_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className : GENERIC_EXCEPTION_CONTENT_TO_ERROR_MAPPING.className;
    }

    private static int estimatedSize(MappingListGroup group) {
        return LIST_DECLARATION_SIZE_ESTIMATE
                + group.mappingNames().size() * LIST_REFERENCE_SIZE_ESTIMATE
                + group.operationNames().size() * MAP_PUT_SIZE_ESTIMATE;
    }

    private int estimatedTotalSize(ProviderContent content) {
        return content.mappings().size() * MAPPING_DECLARATION_SIZE_ESTIMATE
                + content.listGroups().stream().mapToInt(ExceptionToErrorMappingProviderGenerator::estimatedSize).sum();
    }

    private CodeBlock createExceptionToErrorMappingCodeBlock(ExceptionToErrorMapping exceptionToErrorMapping) {
        var isDatabase = switch (exceptionToErrorMapping.getHandler()) {
            case DATABASE -> true;
            case GENERIC -> false;
        };
        var contentToErrorMappingClassName = mappingType(exceptionToErrorMapping.getHandler());
        var contentClassName = isDatabase ? DATA_ACCESS_EXCEPTION_MAPPING_CONTENT.className : GENERIC_EXCEPTION_MAPPING_CONTENT.className;
        return CodeBlock.builder()
                .add("new $T(\n", contentToErrorMappingClassName)
                .indent()
                .add("new $T($L, $S),\n",
                        contentClassName,
                        isDatabase
                                ? CodeBlock.of("$S, $S", exceptionToErrorMapping.getDatabaseErrorCode(), exceptionToErrorMapping.getSqlState())
                                : CodeBlock.of("$S", exceptionToErrorMapping.getExceptionClassName()),
                        exceptionToErrorMapping.getExceptionMessageContains())
                .add("(path, $L) -> new $T(path, $L))",
                        MSG_VARIABLE_NAME,
                        processedSchema.getObject(exceptionToErrorMapping.getErrorTypeName()).getGraphClassName(),
                        exceptionToErrorMapping.getErrorDescription().map(it -> CodeBlock.of("$S", it)).orElse(CodeBlock.of(MSG_VARIABLE_NAME)))
                .unindent()
                .build();
    }

    @Override
    public List<TypeSpec> generateAll() {
        if (processedSchema.getExceptions().entrySet().stream().anyMatch(it -> !it.getValue().getExceptionToErrorMappings().isEmpty())) {
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
                .addSuperinterface(EXCEPTION_TO_ERROR_MAPPING_PROVIDER.className)
                .addModifiers(Modifier.PUBLIC)
                .addField(FieldSpec.builder(DATA_ACCESS_ERROR_MAPPINGS_TYPE, DATA_ACCESS_MAPPINGS_FOR_FIELD_NAME, Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(GENERIC_ERROR_MAPPINGS_TYPE, GENERIC_MAPPINGS_FOR_FIELD_NAME, Modifier.PRIVATE, Modifier.FINAL).build())
                .addMethod(createGetDataAccessMappingsForMethod())
                .addMethod(createGetGenericMappingsForMethod());
    }

    private static MethodSpec createGetDataAccessMappingsForMethod() {
        return MethodSpec
                .methodBuilder(asGetMethodName(DATA_ACCESS_MAPPINGS_FOR_FIELD_NAME))
                .returns(DATA_ACCESS_ERROR_MAPPINGS_TYPE)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(OVERRIDE.className)
                .addCode(returnWrap(DATA_ACCESS_MAPPINGS_FOR_FIELD_NAME))
                .build();
    }

    private static MethodSpec createGetGenericMappingsForMethod() {
        return MethodSpec
                .methodBuilder(asGetMethodName(GENERIC_MAPPINGS_FOR_FIELD_NAME))
                .returns(GENERIC_ERROR_MAPPINGS_TYPE)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(OVERRIDE.className)
                .addCode(returnWrap(GENERIC_MAPPINGS_FOR_FIELD_NAME))
                .build();
    }
}
