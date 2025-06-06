package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.ArgumentField;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.codebuilding.NameFormat;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.generators.dependencies.ServiceDependency;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.dto.DTOGenerator.getDTOGetterMethodNameForField;
import static no.sikt.graphitron.mappings.JavaPoetClassName.FUNCTION;
import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_PAGE_INFO_NODE;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class contains common information and operations shared by resolver method generators.
 */
abstract public class ResolverMethodGenerator extends AbstractSchemaMethodGenerator<ObjectField, ObjectDefinition> {
    private static final String LOOKUP_KEYS_NAME = "keys", RESPONSE_NAME = "response", TRANSFORMER_LAMBDA_NAME = "recordTransform";

    public ResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    protected TypeName getReturnTypeName(ObjectField referenceField) {
        if (!referenceField.hasForwardPagination()) {
            return wrapListIf(getTypeName(referenceField), referenceField.isIterableWrapped());
        }
        return processedSchema.getConnectionObject(referenceField).getGraphClassName();
    }

    private TypeName getTypeName(GenerationField field) {
        if (processedSchema.isRecordType(field)) {
            return processedSchema.getRecordType(field).getGraphClassName();
        }
        if (processedSchema.isEnum(field)) {
            return processedSchema.getEnum(field).getGraphClassName();
        }
        return field.getTypeClass();
    }

    protected ServiceDependency createServiceDependency(GenerationField target) {
        var dependency = new ServiceDependency(target.getService().getClassName());
        dependencyMap.computeIfAbsent(target.getName(), (s) -> new ArrayList<>()).add(dependency);
        return dependency;
    }

    protected CodeBlock getMethodCall(ObjectField target, InputParser parser, boolean isMutatingMethod) {
        var isService = target.hasServiceReference();
        var hasLookup = !isService && LookupHelpers.lookupExists(target, processedSchema);

        var objectToCall = isService ? uncapitalize(createServiceDependency(target).getName()) : asQueryClass(localObject.getName());
        var methodName = isService ? target.getService().getMethodName() : asQueryMethodName(target.getName(), localObject.getName());
        var isRoot = localObject.isOperationRoot();
        var queryFunction = queryFunction(objectToCall, methodName, parser.getInputParamString(), !isRoot || hasLookup, !isRoot && !hasLookup, isService);

        if (hasLookup) { // Assume all keys are correlated.
            return CodeBlock
                    .builder()
                    .add(declare(LOOKUP_KEYS_NAME, LookupHelpers.getLookupKeysAsList(target, processedSchema)))
                    .addStatement("return $L.$L($N, $L)", newDataFetcher(), "loadLookup", LOOKUP_KEYS_NAME, queryFunction)
                    .build();
        }

        // If this method is the mutating method of a mutation. In other words, it is not the query that returns the final result.
        if (!isMutatingMethod) {
            return callQueryBlock(target, objectToCall, methodName, parser, queryFunction);
        }

        if (isService) {
            return CodeBlock.empty();
        }

        return CodeBlock
                .builder()
                .addStatement(
                        "$T.$L($L$L, $L)",
                        getQueryClassName(objectToCall),
                        methodName,
                        asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME),
                        GeneratorConfig.shouldMakeNodeStrategy() ? CodeBlock.of(", $L", NODE_ID_STRATEGY_NAME) : CodeBlock.empty(),
                        parser.getInputParamString()
                )
                .build();
    }

    private CodeBlock callQueryBlock(ObjectField target, String objectToCall, String method, InputParser parser, CodeBlock queryFunction) {
        return CodeBlock
                .builder()
                .add(fetcherCodeInit(target, localObject))
                .add(callQueryBlockInner(target, objectToCall, method, parser, queryFunction))
                .unindent()
                .unindent()
                .addStatement(")")
                .build();
    }

    private CodeBlock callQueryBlockInner(ObjectField target, String objectToCall, String method, InputParser parser, CodeBlock queryFunction) {
        // Is this query call the result of a delete operation?
        var isDeleteMutationWithoutService = target.hasMutationType() && target.getMutationType().equals(MutationType.DELETE);
        if (isDeleteMutationWithoutService) {
            return CodeBlock.of("$L,\n$L", queryFunction, filterDeleteIDsFunction(target));
        }

        var object = processedSchema.getObjectOrConnectionNode(target);
        var transformFunction = target.hasServiceReference() && object != null
                ? transformOutputRecord(object.getName(), object.hasJavaRecordReference())
                : CodeBlock.empty();
        var transformWrap = transformFunction.isEmpty() ? CodeBlock.empty() : CodeBlock.of(",\n$L", transformFunction);
        if (!target.hasRequiredPaginationFields()) {
            var dataBlock = CodeBlock.builder();
            if (!localObject.isOperationRoot()) {
                dataBlock.add("\n");
            }
            return dataBlock
                    .add(CodeBlock.join(queryFunction, transformWrap))
                    .build();
        }

        var filteredInputs = parser
                .getMethodInputsWithOrderField()
                .keySet()
                .stream()
                .filter(it -> target.getOrderField().map(orderByField -> !orderByField.getName().equals(it)).orElse(true))
                .collect(Collectors.joining(", "));
        var inputsWithKeys = localObject.isOperationRoot() ? filteredInputs : (filteredInputs.isEmpty() ? RESOLVER_KEYS_NAME : RESOLVER_KEYS_NAME + ", " + filteredInputs);
        var contextParams = String.join(", ", processedSchema.getAllContextFields(target).keySet().stream().map(NameFormat::asContextFieldName).toList());
        var allParams = inputsWithKeys.isEmpty() ? contextParams : (contextParams.isEmpty() ? inputsWithKeys : inputsWithKeys + ", " + contextParams);
        var countFunction = countFunction(objectToCall, method, allParams, target.hasServiceReference());
        var connectionFunction = connectionFunction(processedSchema.getConnectionObject(target), processedSchema.getObject(CONNECTION_PAGE_INFO_NODE.getName()));
        return CodeBlock.of(" $N, $L,\n$L,\n$L$L,\n$L", PAGE_SIZE_NAME, GeneratorConfig.getMaxAllowedPageSize(), queryFunction, countFunction, transformWrap, connectionFunction);
    }

    /**
     * @return CodeBlock for the mapping of a record.
     */
    private static CodeBlock transformOutputRecord(String typeName, boolean isJava) {
        return CodeBlock.of("($L, $L) -> $L$L$S)",
                TRANSFORMER_LAMBDA_NAME,
                RESPONSE_NAME,
                recordTransformPart(TRANSFORMER_LAMBDA_NAME, RESPONSE_NAME, typeName, isJava, false),
                shouldMakeNodeStrategy() ? CodeBlock.of("$N, ", NODE_ID_STRATEGY_NAME) : CodeBlock.empty(),
                ""
        );
    }

    /**
     * @return CodeBlock for the filtering of IDs. Does not use recursion to check for arbitrarily deep nesting.
     */
    private CodeBlock filterDeleteIDsFunction(ObjectField target) {
        var isRecord = processedSchema.isRecordType(target);
        var recordType = processedSchema.getRecordType(target);
        var outputIDFieldOptional = isRecord
                ? recordType.getFields().stream().filter(FieldSpecification::isID).findFirst()
                : Optional.of(target);

        var argIDField = deduceIDInputFieldFromArgs(target);
        GenerationField idContainerField, idField;
        if (argIDField != null) {
            idContainerField = null;
            idField = argIDField;
        } else {
            idContainerField = deduceIDInputFieldType(target);
            idField = idContainerField == null ? null : processedSchema
                    .getRecordType(idContainerField)
                    .getFields()
                    .stream()
                    .filter(FieldSpecification::isID)
                    .findFirst()
                    .orElseThrow();  // This will not happen, already checked in previous filter that this exists.
        }

        if (outputIDFieldOptional.isEmpty() || idField == null || !outputIDFieldOptional.get().isID()) {
            return CodeBlock.of("$L.identity()", FUNCTION.className);
        }

        var outputIDField = outputIDFieldOptional.get();

        var extraction = CodeBlock.builder().add(VARIABLE_RESULT);
        if (isRecord) {
            extraction.add(outputIDField.getMappingFromSchemaName().asGetCall());
        }

        var filter = CodeBlock.builder();
        if (outputIDField.isIterableWrapped()) {
            filter.add(
                    "$1L.stream().map($2L -> $2N$3L).filter($2L -> !$4L.contains($2N))$5L",
                    idContainerField == null ? idField.getName() : idContainerField.getName(),
                    VARIABLE_INTERNAL_ITERATION,
                    idField.getMappingFromSchemaName().asGetCall(),
                    extraction.build(),
                    collectToList()
            );
        } else {
            filter.add(
                    "$L == null ? $L : null",
                    extraction.build(),
                    idContainerField == null
                            ? idField.getName()
                            : CodeBlock.of("$N$L", idContainerField.getName(), idField.getMappingFromSchemaName().asGetCall()));
        }

        var reWrapping = CodeBlock.builder().add("($L) -> ", VARIABLE_RESULT);
        if (isRecord) {
            return reWrapping.add("new $T($L)", recordType.getGraphClassName(), filter.build()).build();
        }

        return reWrapping.add(filter.build()).build();
    }

    private ArgumentField deduceIDInputFieldType(ObjectField target) {
        return target
                .getArguments()
                .stream()
                .filter(it ->
                        Optional
                                .of(processedSchema.getRecordType(it))
                                .map(record -> record.getFields().stream().anyMatch(FieldSpecification::isID))
                                .orElse(false)
                )
                .findFirst()
                .orElse(null);
    }

    private ArgumentField deduceIDInputFieldFromArgs(ObjectField target) {
        return target.getArguments().stream().filter(AbstractField::isID).findFirst().orElse(null);
    }

    private static CodeBlock fetcherCodeInit(ObjectField target, RecordObjectSpecification<?> localObject) {
        var code = CodeBlock
                .builder()
                .add(
                        "return $L.$L(\n",
                        target.hasServiceReference() ? newServiceDataFetcherWithTransform() : newDataFetcher(),
                        getFetcherMethodName(target, localObject)
                )
                .indent()
                .indent();
        if (!localObject.isOperationRoot()) {
            code.add("$N.$L(),", uncapitalize(localObject.getName()), getDTOGetterMethodNameForField(target));
        }
        return code.build();
    }

    private static String getFetcherMethodName(ObjectField target, RecordObjectSpecification<?> localObject) {
        if (target.hasMutationType() && target.getMutationType().equals(MutationType.DELETE)) {
            return "loadDelete";
        }

        if (!localObject.isOperationRoot() && target.isIterableWrapped() && target.isNonNullable())  {
            return "loadNonNullable";
        }

        return target.hasForwardPagination() ? "loadPaginated" : "load";
    }

    /**
     * @return Code that declares any service dependencies set for this generator.
     */
    protected CodeBlock declareAllServiceClasses(String methodName) {
        var code = CodeBlock.builder();
        dependencyMap
                .getOrDefault(methodName, List.of())
                .stream()
                .filter(dep -> dep instanceof ServiceDependency) // Inelegant solution, but it should work for now.
                .distinct()
                .sorted()
                .map(dep -> (ServiceDependency) dep)
                .forEach(dep -> code.add(dep.getDeclarationCode()));
        return code.build();
    }

    /**
     * @return CodeBlock for declaring the transformer class and calling it on each record input.
     */
    protected CodeBlock transformInputs(ObjectField field, InputParser parser) {
        if (!parser.hasRecords()) {
            if (field.hasServiceReference()) {
                return declareTransform();
            }

            return CodeBlock.empty();
        }

        return inputTransform(field.getNonReservedArguments());
    }

    /**
     * @return CodeBlock for declaring the transformer class and calling it on each record input.
     */
    protected CodeBlock transformInputs(List<? extends InputField> inputs, boolean hasRecords) {
        if (!hasRecords) {
            return declareTransform();
        }

        return inputTransform(inputs);
    }

    private CodeBlock inputTransform(List<? extends InputField> specInputs) {
        var code = CodeBlock
                .builder()
                .add(declareTransform())
                .add("\n");
        var recordCode = CodeBlock.builder();

        var inputObjects = specInputs.stream().filter(processedSchema::isInputType).toList();
        for (var in : inputObjects) {
            var context = MapperContext.createResolverContext(in, true, processedSchema);
            code.add(declareRecords(context, 0));
            recordCode.add(unwrapRecords(context));
        }

        if (code.isEmpty() && recordCode.isEmpty()) {
            return CodeBlock.empty();
        }

        code.add("\n").add(recordCode.build());

        if (recordValidationEnabled()) {
            code.add("\n").addStatement(asMethodCall(TRANSFORMER_NAME, METHOD_VALIDATE_NAME));
        }

        return code.build();
    }

    private CodeBlock declareRecords(MapperContext context, int recursion) {
        recursionCheck(recursion);
        if (!context.targetIsType()) {
            return CodeBlock.empty();
        }

        var target = context.getTarget();
        var targetName = target.getName();
        var code = CodeBlock.builder();
        var declareBlock = declare(asListedRecordNameIf(targetName, context.isIterable()), context.transformInputRecord());
        if (context.hasJavaRecordReference()) {
            return declareBlock; // If the input type is a Java record, no further records should be declared.
        }

        if (context.hasTable() && recursion == 0) {
            code.add(declareBlock);
        } else {
            code.add(declareRecord(asRecordName(target.getName()), context.getTargetType(), context.isIterable(), true));
        }

        context
                .getTargetType()
                .getFields()
                .stream()
                .filter(processedSchema::isInputType)
                .forEach(in -> code.add(declareRecords(context.iterateContext(in), recursion + 1)));

        return code.build();
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    private CodeBlock unwrapRecords(MapperContext context) {
        if (context.hasJavaRecordReference()) {
            return CodeBlock.empty();
        }

        var schema = context.getSchema();

        var containedInputTypes = context.getTargetType()
                .getInputsSortedByNullability()
                .stream()
                .filter(schema::isInputType)
                .filter(it -> schema.hasRecord(it) || schema.getInputType(it).getFields().stream().anyMatch(schema::isInputType))
                .toList();

        var fieldCode = CodeBlock.builder();
        for (var in : containedInputTypes) {
            var innerContext = context.iterateContext(in);
            fieldCode
                    .add(declare(in.getName(), innerContext.getSourceGetCallBlock()))
                    .add(unwrapRecords(innerContext));
        }

        var code = CodeBlock.builder();
        var sourceName = context.getSourceName();
        if (context.hasTable() && !context.isTopLevelContext()) {
            var record = context.transformInputRecord();
            if (!context.getPreviousContext().wasIterable()) {
                code.addStatement("$L = $L", asListedRecordNameIf(sourceName, context.isIterable()), record);
            } else {
                code.addStatement("$N.add$L($L)", asListedRecordName(sourceName), context.isIterable() ? "All" : "", record);
            }
        }

        var fields = context.getTargetType().getFields();
        if (fieldCode.isEmpty() || fields.stream().noneMatch(schema::isInputType)) {
            return code.build();
        }

        if (context.isIterable() && !(context.hasTable() && fields.stream().anyMatch(it -> it.isIterableWrapped() && schema.isInputType(it)))) {
            return code.build();
        }

        return wrapNotNull(sourceName, code.add(context.wrapFields(fieldCode.build())).build());
    }

    protected CodeBlock declareContextArgs(ObjectField target) {
        var contextFields = processedSchema.getAllContextFields(target);
        if (contextFields.isEmpty()) {
            return CodeBlock.empty();
        }

        var code = CodeBlock
                .builder()
                .add(declare(GRAPH_CONTEXT_NAME, asMethodCall(VARIABLE_ENV, METHOD_GRAPH_CONTEXT)));
        contextFields.forEach((name, type) -> code.add(declare(asContextFieldName(name), asCast(type, CodeBlock.of("$N.get($S)", GRAPH_CONTEXT_NAME, name)))));
        return code.build();
    }
}
