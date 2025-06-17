package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.generators.dependencies.ServiceDependency;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.dto.DTOGenerator.getDTOGetterMethodNameForField;
import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_PAGE_INFO_NODE;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class contains common information and operations shared by resolver method generators.
 */
abstract public class ResolverMethodGenerator extends AbstractSchemaMethodGenerator<ObjectField, ObjectDefinition> {
    protected static final String LOOKUP_KEYS_NAME = "keys", RESPONSE_NAME = "response", TRANSFORMER_LAMBDA_NAME = "recordTransform";

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
        var dependency = new ServiceDependency(target.getService().getServiceClassName());
        dependencyMap.computeIfAbsent(target.getName(), (s) -> new ArrayList<>()).add(dependency);
        return dependency;
    }

    protected CodeBlock getMethodCall(ObjectField target, InputParser parser, boolean isMutation) {
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

        if (isMutation) {
            var methodCall = CodeBlock
                    .builder()
                    .add(isService ? CodeBlock.of("$N", uncapitalize(objectToCall)) : CodeBlock.of("$T", getQueryClassName(objectToCall)))
                    .add(".$L(", methodName)
                    .add(isService ? empty() : CodeBlock.of("$L, ", asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME)))
                    .add("$L)", parser.getInputParamString());
            return declare(asResultName(target.getName()), methodCall.build());
        }

        return callQueryBlock(target, objectToCall, methodName, parser, queryFunction);
    }

    private CodeBlock callQueryBlock(ObjectField target, String objectToCall, String method, InputParser parser, CodeBlock queryFunction) {
        var isService = target.hasServiceReference();
        var dataBlock = CodeBlock
                .builder()
                .add(fetcherCodeInit(target, localObject, isService ? newServiceDataFetcherWithTransform() : newDataFetcher()));

        var object = processedSchema.getObjectOrConnectionNode(target);
        var transformFunction = isService && object != null
                ? transformOutputRecord(object.getName(), object.hasJavaRecordReference())
                : empty();
        var transformWrap = transformFunction.isEmpty() ? empty() : CodeBlock.of(",\n$L", transformFunction);
        if (!target.hasRequiredPaginationFields()) {
            if (!localObject.isOperationRoot()) {
                dataBlock.add("\n");
            }
            return dataBlock
                    .add(join(queryFunction, transformWrap))
                    .unindent()
                    .unindent()
                    .addStatement(")")
                    .build();
        }

        var filteredInputs = parser
                .getMethodInputsWithOrderField()
                .keySet()
                .stream()
                .filter(it -> target.getOrderField().map(orderByField -> !orderByField.getName().equals(it)).orElse(true))
                .collect(Collectors.joining(", "));
        var inputsWithKeys = localObject.isOperationRoot() ? filteredInputs : (filteredInputs.isEmpty() ? RESOLVER_KEYS_NAME : RESOLVER_KEYS_NAME + ", " + filteredInputs);
        var contextParams = isService ? String.join(", ", target.getService().getContextFields().keySet().stream().map(it -> "_" + it).toList()) : "";
        var allParams = inputsWithKeys.isEmpty() ? contextParams : (contextParams.isEmpty() ? inputsWithKeys : inputsWithKeys + ", " + contextParams);
        var countFunction = countFunction(objectToCall, method, allParams, isService);
        var connectionFunction = connectionFunction(processedSchema.getConnectionObject(target), processedSchema.getObject(CONNECTION_PAGE_INFO_NODE.getName()));
        return dataBlock
                .add(" $N, $L,\n$L,\n$L$L,\n$L", PAGE_SIZE_NAME, GeneratorConfig.getMaxAllowedPageSize(), queryFunction, countFunction, transformWrap, connectionFunction)
                .unindent()
                .unindent()
                .addStatement(")")
                .build();
    }

    /**
     * @return CodeBlock for the mapping of a record.
     */
    private static CodeBlock transformOutputRecord(String typeName, boolean isJava) {
        return CodeBlock.of("($L, $L) -> $L$L$S)",
                TRANSFORMER_LAMBDA_NAME,
                RESPONSE_NAME,
                recordTransformPart(TRANSFORMER_LAMBDA_NAME, RESPONSE_NAME, typeName, isJava, false),
                shouldMakeNodeStrategy() ? CodeBlock.of("$N, ", NODE_ID_STRATEGY_NAME) : empty(),
                ""
        );
    }

    private static CodeBlock fetcherCodeInit(ObjectField target, RecordObjectSpecification<?> localObject, CodeBlock fetcher) {
        var methodName = !localObject.isOperationRoot() && target.isIterableWrapped() && target.isNonNullable()
                ? "loadNonNullable"
                : target.hasForwardPagination() ? "loadPaginated" : "load";
        var code = CodeBlock
                .builder()
                .add("return $L.$L(\n", fetcher, methodName)
                .indent()
                .indent();
        if (!localObject.isOperationRoot()) {
            code.add("$N.$L(),", uncapitalize(localObject.getName()), getDTOGetterMethodNameForField(target));
        }
        return code.build();
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

            return empty();
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
            return empty();
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
            return empty();
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
            return empty();
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
        if (!target.hasServiceReference() || target.getService().getContextFields().isEmpty()) {
            return empty();
        }

        var code = CodeBlock
                .builder()
                .add(declare(GRAPH_CONTEXT_NAME, asMethodCall(VARIABLE_ENV, METHOD_GRAPH_CONTEXT)));
        target.getService().getContextFields().forEach((name, type) -> code.add(declare("_" + name, asCast(type, CodeBlock.of("$N.get($S)", GRAPH_CONTEXT_NAME, name)))));
        return code.build();
    }
}
