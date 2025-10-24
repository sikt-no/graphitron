package no.sikt.graphitron.generators.datafetchers.operations;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.codebuilding.VariablePrefix;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.dto.DTOGenerator.getDTOGetterMethodNameForField;
import static no.sikt.graphql.naming.GraphQLReservedName.*;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the data fetchers for default fetch or mutation queries with potential arguments or pagination.
 */
public class OperationMethodGenerator extends DataFetcherMethodGenerator {
    private static final String LOOKUP_KEYS_NAME = "keys", RESPONSE_NAME = "response", TRANSFORMER_LAMBDA_NAME = "recordTransform";

    public OperationMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var parser = new InputParser(target, processedSchema, !target.isDeleteMutation());
        var methodCall = getMethodCall(target, parser, false); // Note, do this before declaring services.
        dataFetcherWiring.add(new WiringContainer(target.getName(), getLocalObject().getName(), target.getName()));
        return getDefaultSpecBuilder(target.getName(), wrapFetcher(wrapFuture(getReturnTypeName(target))))
                .beginControlFlow("return $N ->", VARIABLE_ENV)
                .addCode(extractParams(target))
                .addCode(declareContextArgs(target))
                .addCodeIf(!target.isDeleteMutation() || recordValidationEnabled(), () -> transformInputs(target, parser))
                .addCode(declareAllServiceClasses(target.getName()))
                .addCodeIf(!target.isDeleteMutation() && localObject.getName().equals(SCHEMA_MUTATION.getName()),
                        () -> getMethodCall(target, parser, true))
                .addCode(methodCall)
                .endControlFlow("") // Keep this, logic to set semicolon only kicks in if a string is set.
                .build();
    }

    protected CodeBlock getMethodCall(ObjectField target, InputParser parser, boolean isMutatingMethod) {
        var isService = target.hasServiceReference();
        var hasLookup = !isService && LookupHelpers.lookupExists(target, processedSchema);

        var objectToCall = isService ? uncapitalize(createServiceDependency(target).getName()) : asQueryClass(localObject.getName());
        var methodName = isService ? target.getExternalMethod().getMethodName() : asQueryMethodName(target.getName(), localObject.getName());
        var isRoot = localObject.isOperationRoot();
        var queryFunction = queryFunction(objectToCall, methodName, parser.getInputParamString(), !isRoot || hasLookup, !isRoot && !hasLookup, isService);

        if (hasLookup) { // Assume all keys are correlated.
            return CodeBlock
                    .builder()
                    .declare(LOOKUP_KEYS_NAME, LookupHelpers.getLookupKeysAsList(target, processedSchema))
                    .addStatement("return $L.$L($N, $L)", newDataFetcher(), "loadLookup", LOOKUP_KEYS_NAME, queryFunction)
                    .build();
        }

        if (processedSchema.isOrderedMultiKeyQuery(target)) {
            return CodeBlock.statementOf(
                    "return $L.$L($L, $L)",
                    newDataFetcher(),
                    "loadByResolverKeys",
                    asMethodCall(uncapitalize(localObject.getName()), getDTOGetterMethodNameForField(target)),
                    queryFunction
            );
        }

        // If this method is the mutating method of a mutation. In other words, it is not the query that returns the final result.
        if (!isMutatingMethod) {
            return callQueryBlock(target, objectToCall, methodName, parser, queryFunction);
        }

        if (isService) {
            return CodeBlock.empty();
        }

        return CodeBlock.statementOf(
                "$T.$L($L$L, $L)",
                getQueryClassName(objectToCall),
                methodName,
                asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME),
                CodeBlock.ofIf(shouldMakeNodeStrategy(), ", $L", NODE_ID_STRATEGY_NAME),
                parser.getInputParamString()
        );
    }

    private CodeBlock callQueryBlock(ObjectField target, String objectToCall, String method, InputParser parser, CodeBlock queryFunction) {
        var innerCode = CodeBlock
                .builder()
                .addIf(!localObject.isOperationRoot(), "$L,", asMethodCall(uncapitalize(localObject.getName()), getDTOGetterMethodNameForField(target)))
                .add(callQueryBlockInner(target, objectToCall, method, parser, queryFunction))
                .build();
        return CodeBlock
                .builder()
                .add("return $L.$L($L", target.hasServiceReference() ? newServiceDataFetcherWithTransform() : newDataFetcher(), getFetcherMethodName(target, localObject), indentIfMultiline(innerCode))
                .addStatement(")")
                .build();
    }

    private CodeBlock callQueryBlockInner(ObjectField target, String objectToCall, String method, InputParser parser, CodeBlock queryFunction) {
        // Is this query call the result of a delete operation?
        if (target.isDeleteMutation()) {
            return !processedSchema.inferDataTargetForMutation(target).map(target::equals).orElse(false) ?
                    CodeBlock.of("$L,\n$L", queryFunction, wrapMutationOutputFunction(target)) :  queryFunction;
        }

        var object = processedSchema.getObjectOrConnectionNode(target);
        var transformFunction = target.hasServiceReference() && object != null
                ? transformOutputRecord(object.getName(), object.hasJavaRecordReference())
                : CodeBlock.empty();
        var transformWrap = CodeBlock.ofIf(!transformFunction.isEmpty(), ",\n$L", transformFunction);
        if (!target.hasRequiredPaginationFields()) {
            return CodeBlock
                    .builder()
                    .addIf(!localObject.isOperationRoot(), "\n")
                    .addAll(queryFunction, transformWrap)
                    .build();
        }

        var filteredInputs = parser
                .getMethodInputsWithOrderField()
                .keySet()
                .stream()
                .filter(it -> target.getOrderField().map(orderByField -> !orderByField.getName().equals(it)).orElse(true))
                .collect(Collectors.joining(", "));
        var inputsWithKeys = localObject.isOperationRoot() ? filteredInputs : (filteredInputs.isEmpty() ? RESOLVER_KEYS_NAME : RESOLVER_KEYS_NAME + ", " + filteredInputs);
        var contextParams = String.join(", ", processedSchema.getAllContextFields(target).keySet().stream().map(VariablePrefix::contextFieldPrefix).toList());
        var allParams = inputsWithKeys.isEmpty() ? contextParams : (contextParams.isEmpty() ? inputsWithKeys : inputsWithKeys + ", " + contextParams);
        var countFunction = countFunction(objectToCall, method, allParams, target.hasServiceReference());
        return CodeBlock.of(" $N,\n$L,\n$L$L", PAGE_SIZE_NAME, queryFunction, countFunction, transformWrap);
    }

    /**
     * @return CodeBlock for the mapping of a record.
     */
    private static CodeBlock transformOutputRecord(String typeName, boolean isJava) {
        return CodeBlock.of(
                "($L, $L) -> $L$L$S)",
                TRANSFORMER_LAMBDA_NAME,
                RESPONSE_NAME,
                recordTransformPart(TRANSFORMER_LAMBDA_NAME, RESPONSE_NAME, typeName, isJava, false),
                CodeBlock.ofIf(shouldMakeNodeStrategy(), "$N, ", NODE_ID_STRATEGY_NAME),
                ""
        );
    }

    /**
     * @return CodeBlock for wrapping output from mutations. Does not use recursion to check for arbitrarily deep nesting.
     */
    private CodeBlock wrapMutationOutputFunction(ObjectField target) {
        var isRecord = processedSchema.isRecordType(target);
        if (!isRecord) return CodeBlock.empty();
        var recordType = processedSchema.getRecordType(target);
        return  CodeBlock.of("($1N) -> new $2T($1N)", VARIABLE_RESULT, recordType.getGraphClassName());
    }

    private String getFetcherMethodName(ObjectField target, RecordObjectSpecification<?> localObject) {
        if (target.isDeleteMutation()) {
            return processedSchema.isObject(target) && !processedSchema.hasTableObject(target) ? "loadWrapped" : "load";
        }

        if (!localObject.isOperationRoot() && target.isIterableWrapped() && target.isNonNullable())  {
            return "loadNonNullable";
        }

        return target.hasForwardPagination() ? "loadPaginated" : "load";
    }

    /**
     * @return CodeBlock for declaring the transformer class and calling it on each record input.
     */
    private CodeBlock transformInputs(ObjectField field, InputParser parser) {
        if (!parser.hasRecords()) {
            if (field.hasServiceReference()) {
                return declareTransform();
            }

            return CodeBlock.empty();
        }

        var code = CodeBlock
                .builder()
                .add(declareTransform())
                .add("\n");
        var recordCode = CodeBlock.builder();

        var inputObjects = field.getNonReservedArguments().stream().filter(processedSchema::isInputType).toList();
        for (var in : inputObjects) {
            var context = MapperContext.createResolverContext(in, true, processedSchema);
            code.add(declareRecords(context, 0));
            recordCode.add(unwrapRecords(context));
        }

        if (code.isEmpty() && recordCode.isEmpty()) {
            return CodeBlock.empty();
        }

        return code
                .add("\n")
                .add(recordCode.build())
                .addIf(recordValidationEnabled(), "\n")
                .addStatementIf(recordValidationEnabled(), asMethodCall(TRANSFORMER_NAME, METHOD_VALIDATE_NAME))
                .build();
    }

    private CodeBlock declareRecords(MapperContext context, int recursion) {
        recursionCheck(recursion);
        if (!context.targetIsType()) {
            return CodeBlock.empty();
        }

        var target = context.getTarget();
        var targetName = target.getName();
        var declareBlock = CodeBlock.declare(asListedRecordNameIf(targetName, context.isIterable()), context.transformInputRecord());
        if (context.hasJavaRecordReference()) {
            return declareBlock; // If the input type is a Java record, no further records should be declared.
        }

        var code = CodeBlock.builder();
        if (context.hasTable() && recursion == 0) {
            code.add(declareBlock);
        } else {
            code.add(declareRecord(asRecordName(target.getName()), context.getTargetType(), context.isIterable(), true));
        }

        code.addAll(
                context
                        .getTargetType()
                        .getFields()
                        .stream()
                        .filter(processedSchema::isInputType)
                        .map(in -> declareRecords(context.iterateContext(in), recursion + 1))
                        .toList()
        );

        return code.build();
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    private CodeBlock unwrapRecords(MapperContext context) {
        if (context.hasJavaRecordReference()) {
            return CodeBlock.empty();
        }

        var containedInputTypes = context.getTargetType()
                .getInputsSortedByNullability()
                .stream()
                .filter(processedSchema::isInputType)
                .filter(it -> processedSchema.hasRecord(it) || processedSchema.getInputType(it).getFields().stream().anyMatch(processedSchema::isInputType))
                .toList();

        var fieldCode = CodeBlock.builder();
        for (var in : containedInputTypes) {
            var innerContext = context.iterateContext(in);
            fieldCode
                    .declare(in.getName(), innerContext.getSourceGetCallBlock())
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
        if (fieldCode.isEmpty() || fields.stream().noneMatch(processedSchema::isInputType)) {
            return code.build();
        }

        if (context.isIterable() && !(context.hasTable() && fields.stream().anyMatch(it -> it.isIterableWrapped() && processedSchema.isInputType(it)))) {
            return code.build();
        }

        return wrapNotNull(sourceName, code.add(context.wrapFields(fieldCode.build())).build());
    }

    private CodeBlock declareContextArgs(ObjectField target) {
        var contextFields = processedSchema.getAllContextFields(target);
        if (contextFields.isEmpty()) {
            return CodeBlock.empty();
        }

        var code = CodeBlock
                .builder()
                .declare(GRAPH_CONTEXT_NAME, asMethodCall(VARIABLE_ENV, METHOD_GRAPH_CONTEXT))
                .declare(VARIABLE_GRAPHITRON_CONTEXT, CodeBlock.of("$N.get($S)", GRAPH_CONTEXT_NAME, GRAPHITRON_CONTEXT_NAME));

        contextFields.forEach((name, type) -> code.declare(type, VariablePrefix.contextFieldPrefix(name), CodeBlock.of("$N.getContextArgument($L, $S)", VARIABLE_GRAPHITRON_CONTEXT, VARIABLE_ENV, name)));
        return code.build();
    }

    protected TypeName getReturnTypeName(ObjectField referenceField) {
        var typeName = getTypeName(referenceField);
        if (referenceField.hasForwardPagination()) {
            return wrapConnection(typeName);
        }

        return wrapListIf(typeName, referenceField.isIterableWrapped());
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

    protected boolean generationCondition(GenerationField target) {
        if (!target.isGeneratedWithResolver()) {
            return false;
        }

        if (target.getName().equals(FEDERATION_ENTITIES_FIELD.getName())) {
            return false;
        }

        if (processedSchema.isFederationService(target)) {
            return false;
        }

        if (processedSchema.isInterface(target) && target.getTypeName().equals(NODE_TYPE.getName())) {
            return false;
        }

        if (target.hasServiceReference() && !localObject.getName().equals(SCHEMA_MUTATION.getName())) {
            return !LookupHelpers.lookupExists((ObjectField) target, processedSchema);
        }

        return true;
    }

    @Override
    public List<MethodSpec> generateAll() {
        var localObject = getLocalObject();
        if (localObject == null || localObject.isExplicitlyNotGenerated()) {
            return List.of();
        }

        return localObject
                .getFields()
                .stream()
                .filter(this::generationCondition)
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .toList();
    }
}
