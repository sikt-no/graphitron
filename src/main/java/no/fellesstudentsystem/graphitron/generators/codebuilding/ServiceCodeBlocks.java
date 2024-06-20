package no.fellesstudentsystem.graphitron.generators.codebuilding;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.fields.OrderByEnumField;
import no.fellesstudentsystem.graphitron.definitions.helpers.ServiceWrapper;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.CONNECTION_PAGE_INFO_NODE;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.PAGINATION_AFTER;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class ServiceCodeBlocks {
    public static CodeBlock inputTransform(List<? extends InputField> specInputs, ProcessedSchema schema) {
        var code = CodeBlock.builder();
        var recordCode = CodeBlock.builder();

        var inputObjects = specInputs.stream().filter(schema::isInputType).collect(Collectors.toList());
        for (var in : inputObjects) {
            code.add(declareRecords(in, schema, 0));
            recordCode.add(unwrapRecords(MapperContext.createResolverContext(in, true, schema)));
        }

        if (code.isEmpty() && recordCode.isEmpty()) {
            return empty();
        }

        code.add("\n").add(recordCode.build());

        if (recordValidationEnabled()) {
            code.add("\n").addStatement(asMethodCall(TRANSFORMER_NAME, TransformerClassGenerator.METHOD_VALIDATE_NAME));
        }

        return code.build();
    }

    private static CodeBlock declareRecords(InputField target, ProcessedSchema schema, int recursion) {
        recursionCheck(recursion);

        var input = schema.getInputType(target);
        if (!input.hasRecordReference()) {
            return empty();
        }

        var targetName = target.getName();
        var code = CodeBlock.builder();
        var declareBlock = declare(asListedRecordNameIf(targetName, target.isIterableWrapped()), transformRecord(targetName, target.getTypeName(), input.hasJavaRecordReference()));
        if (input.hasJavaRecordReference()) {
            return declareBlock; // If the input type is a Java record, no further records should be declared.
        }

        if (input.hasTable() && recursion == 0) {
            code.add(declareBlock);
        } else {
            code.add(declareRecord(asRecordName(target.getName()), input, target.isIterableWrapped()));
        }

        input
                .getFields()
                .stream()
                .filter(schema::isInputType)
                .forEach(in -> code.add(declareRecords(in, schema, recursion + 1)));

        return code.build();
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    private static CodeBlock unwrapRecords(MapperContext context) {
        if (context.hasJavaRecordReference()) {
            return empty();
        }

        var schema = context.getSchema();

        var containedInputTypes = context.getTargetType()
                .getInputsSortedByNullability()
                .stream()
                .filter(schema::isInputType)
                .filter(it -> schema.isTableInputType(it) || schema.isJavaRecordType(it) || schema.getInputType(it).getFields().stream().anyMatch(schema::isInputType))
                .collect(Collectors.toList());

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
            var record = transformRecord(sourceName, context.getTarget().getTypeName(), context.getPath(), context.getIndexPath(), false);
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

    // Does not actually belong here since it does not only deal with services.
    public static CodeBlock callQueryBlock(ObjectField target, String objectToCall, String method, ArrayList<String> allQueryInputs, RecordObjectSpecification<?> localObject, CodeBlock queryFunction, CodeBlock transformFunction, boolean isService, ProcessedSchema schema) {
        var queryMethodName = asQueryMethodName(target.getName(), localObject.getName());
        var dataBlock = CodeBlock
                .builder()
                .add(fetcherCodeInit(target, queryMethodName, localObject, isService ? newDataFetcherWithTransform() : newDataFetcher()));

        var transformWrap = transformFunction.isEmpty() ? empty() : CodeBlock.of(",\n$L", transformFunction);
        if (!target.hasRequiredPaginationFields()) {
            if (!localObject.isOperationRoot()) {
                dataBlock.add("\n");
            }
            return dataBlock
                    .add("$L$L", queryFunction, transformWrap)
                    .unindent()
                    .unindent()
                    .addStatement(")")
                    .build();
        }

        var filteredInputs = allQueryInputs
                .stream()
                .filter(it -> !it.equals(PAGE_SIZE_NAME) && !it.equals(PAGINATION_AFTER.getName()) && !it.equals(SELECTION_SET_NAME) &&
                        target.getOrderField().map(AbstractField::getName).map(orderByField -> !orderByField.equals(it)).orElse(true))
                .collect(Collectors.joining(", "));
        var inputsWithId = localObject.isOperationRoot() ? filteredInputs : (filteredInputs.isEmpty() ? IDS_NAME : IDS_NAME + ", " + filteredInputs);
        var countFunction = countDBFunction(objectToCall, method, inputsWithId, !isService);
        var connectionFunction = connectionFunction(schema.getConnectionObject(target), schema.getObject(CONNECTION_PAGE_INFO_NODE.getName()));
        return dataBlock
                .add("$N, $L,\n$L,\n$L,\n$L$L,\n$L", PAGE_SIZE_NAME, GeneratorConfig.getMaxAllowedPageSize(), queryFunction, countFunction, getIDFunction(target, schema), transformWrap, connectionFunction)
                .unindent()
                .unindent()
                .addStatement(")")
                .build();
    }

    public static CodeBlock fetcherCodeInit(ObjectField target, String queryMethodName, RecordObjectSpecification<?> localObject, CodeBlock fetcher) {
        var methodName = !localObject.isOperationRoot() && target.isIterableWrapped() && target.isNonNullable()
                ? "loadNonNullable"
                : target.hasForwardPagination() ? "loadPaginated" : "load";
        var code = CodeBlock
                .builder()
                .add("return $L.$L(\n", fetcher, methodName)
                .indent()
                .indent();
        if (!localObject.isOperationRoot()) {
            code.add("$S, $N.getId(), ", queryMethodName, uncapitalize(localObject.getName()));
        }
        return code.build();
    }

    /**
     * @return CodeBlock consisting of a function for a getId call.
     */
    private static CodeBlock getIDFunction(ObjectField referenceField, ProcessedSchema schema) {
        return referenceField
                .getOrderField()
                .map(orderInputField -> {
                    var objectNode = schema.getObjectOrConnectionNode(referenceField);

                    var orderByFieldMapEntries = schema.getOrderByFieldEnum(orderInputField)
                            .getFields()
                            .stream()
                            .map(orderByField -> CodeBlock.of("$S, $L -> $L",
                                    orderByField.getName(), VARIABLE_TYPE_NAME, createJoinedGetFieldAsStringCallBlock(orderByField, objectNode, schema)))
                            .collect(CodeBlock.joining(",\n"));

                    return CodeBlock.builder()
                            .add("(it) -> $N == null ? it.getId() :\n", orderInputField.getName())
                            .indent()
                            .indent()
                            .add("$T.<$T, $T<$T, $T>>of(\n",
                                    MAP.className, STRING.className, FUNCTION.className, objectNode.getGraphClassName(), STRING.className)
                            .indent()
                            .indent()
                            .add("$L\n", orderByFieldMapEntries)
                            .unindent()
                            .unindent()
                            .add(").get($L.get$L().toString()).apply(it)", orderInputField.getName(), capitalize(GraphQLReservedName.ORDER_BY_FIELD.getName()))
                            .unindent()
                            .unindent()
                            .build();
                })
                .orElseGet(() -> CodeBlock.of("(it) -> it.getId()")); //Note: getID is FS-specific.
    }

    private static CodeBlock createJoinedGetFieldAsStringCallBlock(OrderByEnumField orderByField, ObjectDefinition objectNode, ProcessedSchema schema) {
        return orderByField.getSchemaFieldsWithPathForIndex(schema, objectNode)
                .entrySet()
                .stream()
                .map(fieldWithPath -> createNullSafeGetFieldAsStringCall(fieldWithPath.getKey(), fieldWithPath.getValue()))
                .collect(CodeBlock.joining(" + \",\" + "));
    }

    private static CodeBlock createNullSafeGetFieldAsStringCall(ObjectField field,  List<String> path) {
        var getFieldCall = field.getMappingFromSchemaName().asGetCall();
        var fullCallBlock = CodeBlock.of("$L$L$L", VARIABLE_TYPE_NAME, path.stream().map(it -> new MethodMapping(it).asGetCall()).collect(CodeBlock.joining("")), getFieldCall);

        if (field.getTypeClass() != null &&
                (field.getTypeClass().isPrimitive() || field.getTypeClass().equals(STRING.className))) {
            return fullCallBlock;
        }
        return CodeBlock.of("$L == null ? null : $L.toString()", fullCallBlock, fullCallBlock);
    }

    /**
     * @return Code for adding error types and calling transform methods.
     */
    public static CodeBlock generateSchemaOutputs(MapperContext mapperContext, boolean hasErrors, ServiceWrapper service, ProcessedSchema schema) {
        if (!mapperContext.targetIsType()) {
            return empty();
        }

        var code = CodeBlock.builder();
        if (mapperContext.hasRecordReference() && mapperContext.isTopLevelContext()) {
            code.add(declare(asListedNameIf(mapperContext.getTargetName(), mapperContext.isIterable()), mapperContext.getRecordTransform(mapperContext.getTarget().getName())));
        } else if (!mapperContext.hasRecordReference()) {
            code.add(declareVariable(mapperContext.getTargetName(), mapperContext.getTargetType().getGraphClassName()));
        }

        code.add("\n");

        for (var innerField : mapperContext.getTargetType().getFields()) {
            var innerContext = mapperContext.iterateContext(innerField);
            var previousTarget = innerContext.getPreviousContext().getTarget();

            var innerCode = CodeBlock.builder();
            if (innerContext.shouldUseException()) {
                if (hasErrors) {
                    innerCode.add(innerContext.getSetMappingBlock(asListedName(schema.getErrorTypeDefinition(innerField.getTypeName()).getName())));
                }
            } else if (!innerField.isExplicitlyNotGenerated() && !innerContext.getPreviousContext().hasRecordReference()) {
                if (!innerContext.targetIsType()) {
                    innerCode.add(innerContext.getSetMappingBlock(getFieldSetContent((ObjectField) innerField, (ObjectField) previousTarget, service, schema)));
                } else if (innerContext.shouldUseStandardRecordFetch()) {
                    innerCode.add(innerContext.getRecordSetMappingBlock(previousTarget.getName()));
                } else if (innerContext.hasRecordReference()) {
                    var fetchCode = createIdFetch(innerField, previousTarget.getName(), innerContext.getPath(), true);
                    if (innerContext.isIterable()) {
                        var tempName = asQueryNodeMethod(innerField.getTypeName());
                        innerCode
                                .add(declare(tempName, fetchCode))
                                .add(innerContext.getSetMappingBlock(CodeBlock.of("$N.stream().map(it -> $N.get(it.getId()))$L", previousTarget.getName(), tempName, collectToList())));
                    } else {
                        innerCode.add(innerContext.getSetMappingBlock(fetchCode)); // TODO: Should be done outside for? Preferably devise some general dataloader-like solution applying to query classes.
                    }
                } else {
                    innerCode.add(generateSchemaOutputs(innerContext, hasErrors, service, schema));
                }
            }

            if (!innerCode.isEmpty()) {
                code
                        .beginControlFlow("if ($N != null && $L)", previousTarget.getName(), selectionSetLookup(innerContext.getPath(), true, false))
                        .add(innerCode.build())
                        .endControlFlow()
                        .add("\n");
            }
        }

        return code.add("\n").build();
    }

    public static CodeBlock createIdFetch(GenerationField field, String varName, String path, boolean atResolver) {
        return getNodeQueryCallBlock(field, varName, !atResolver ? CodeBlock.of("$N + $S", PATH_HERE_NAME, path) : CodeBlock.of("$S", path), false, field.isIterableWrapped(), atResolver);
    }

    private static CodeBlock getFieldSetContent(ObjectField field, ObjectField previousField, ServiceWrapper service, ProcessedSchema schema) {
        var resultName = asIterableResultNameIf(previousField.getUnprocessedFieldOverrideInput(), previousField.isIterableWrapped());
        var returnIsMappable = service.getReturnType().getName().endsWith(RECORD_NAME_SUFFIX) || service.returnsJavaRecord();
        if (schema.isObject(previousField) && returnIsMappable) {
            var getMapping = field.getMappingForJOOQFieldOverride();
            var extractValue = field.isIterableWrapped() && !previousField.isIterableWrapped();
            if (extractValue) {
                var iterationName = asIterable(field.getName());
                return CodeBlock.of("$N.stream().map($L -> $L).collect($T.toList())", resultName, iterationName, getValue(iterationName, getMapping), COLLECTORS.className);
            } else {
                return getValue(resultName, getMapping);
            }
        }

        return CodeBlock.of("$N", resultName);
    }

    /**
     * @return This field's name formatted as a method call result.
     */
    public static String getResolverResultName(ObjectField target, ProcessedSchema schema) {
        if (!schema.isObject(target)) {
            return asResultName(target.getUnprocessedFieldOverrideInput());
        }

        return asListedNameIf(target.getTypeName(), target.isIterableWrapped());
    }
}
