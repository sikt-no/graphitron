package no.fellesstudentsystem.graphitron.generators.resolvers.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.fields.OrderByEnumField;
import no.fellesstudentsystem.graphitron.definitions.helpers.ServiceWrapper;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.RecordObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.helpers.queries.LookupHelpers;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator.METHOD_SELECT_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.PAGINATION_AFTER;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for queries with the {@link GenerationDirective#SERVICE} directive set.
 */
public class ServiceFetchResolverMethodGenerator extends FetchResolverMethodGenerator { // TODO: Remove duplicates here. Figure out proper inheritance structure for this.
    private static final String TYPE_NAME = "type", RESPONSE_NAME = "response";
    private ServiceWrapper service;

    public ServiceFetchResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        service = target.hasServiceReference() ? new ServiceWrapper(target, processedSchema) : null;
        if (LookupHelpers.lookupExists(target, processedSchema) || service == null) {
            return MethodSpec.methodBuilder(target.getName()).build();
        }

        var dependency = new ServiceDependency(service.getServiceClassName());
        dependencySet.add(dependency);
        var spec = getDefaultSpecBuilder(target.getName(), getReturnTypeName(target));

        var localObject = getLocalObject();
        if (!localObject.isRoot()) {
            spec.addParameter(localObject.getGraphClassName(), uncapitalize(localObject.getName()));
        }

        var objectToCall = uncapitalize(dependency.getName());
        var serviceMethod = service.getMethod();
        var methodName = uncapitalize(serviceMethod != null ? serviceMethod.getName() : target.getName());

        var allQueryInputs = getQueryInputs(spec, target);
        return spec
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
                .addCode(declareContextVariable())
                .addCode(declareAllServiceClasses())
                .addCode("\n")
                .addCode(transformInputs(target.getNonReservedArgumentsWithOrderField()))
                .addCode(queryMethodCalls(target, objectToCall, methodName, allQueryInputs))
                .build();
    }

    /**
     * @return Code that declares any service dependencies set for this generator.
     */
    private CodeBlock declareAllServiceClasses() {
        var code = CodeBlock.builder();
        dependencySet
                .stream()
                .filter(dep -> dep instanceof ServiceDependency) // Inelegant solution, but it should work for now.
                .distinct()
                .sorted()
                .map(dep -> (ServiceDependency) dep)
                .forEach(dep -> code.add(dep.getDeclarationCode()));
        return code.build();
    }

    @NotNull
    protected ArrayList<String> getQueryInputs(MethodSpec.Builder spec, ObjectField referenceField) {
        var allQueryInputs = new ArrayList<String>();

        referenceField
                .getNonReservedArgumentsWithOrderField()
                .forEach(it -> {
                    var name = it.getName();
                    spec.addParameter(iterableWrap(it), name);
                    allQueryInputs.add(name);
                });

        if (referenceField.hasForwardPagination()) {
            spec
                    .addParameter(INTEGER.className, GraphQLReservedName.PAGINATION_FIRST.getName())
                    .addParameter(STRING.className, PAGINATION_AFTER.getName())
                    .addCode(declarePageSize(referenceField.getFirstDefault()));
            allQueryInputs.add(PAGE_SIZE_NAME);
            allQueryInputs.add(PAGINATION_AFTER.getName());
        }
        return allQueryInputs;
    }

    @NotNull
    private CodeBlock queryMethodCalls(ObjectField target, String objectToCall, String serviceMethod, ArrayList<String> allQueryInputs) {
        var localObject = getLocalObject();
        var isRoot = localObject.isRoot();
        var hasLookup = LookupHelpers.lookupExists(target, processedSchema);
        var hasPagination = target.hasRequiredPaginationFields();

        var inputString = String.join(", ", allQueryInputs);
        if (isRoot && !hasPagination && !hasLookup) {
            return getSimpleRootDBCall(target, serviceMethod, objectToCall, inputString);
        }

        var queryMethodName = asQueryMethodName(target.getName(), localObject.getName());
        var queryFunction = queryDBFunction(objectToCall, serviceMethod, inputString, !isRoot || hasLookup, !isRoot && !hasLookup, false);

        var dataBlock = CodeBlock.builder();
        if (!isRoot) {
            dataBlock
                    .add("return $L.$L(\n", newDataFetcherWithTransform(), target.isIterableWrapped() && target.isNonNullable() ? "loadNonNullable" : "load")
                    .indent()
                    .indent()
                    .add("$S, $N.getId(),", queryMethodName, uncapitalize(localObject.getName())
            );
        } else {
            dataBlock.add("return $L.$L(\n", newDataFetcherWithTransform(), "load").indent().indent();
        }

        var object = processedSchema.getObject(target);
        var transformFunction = object != null
                ? CodeBlock.of("($L, $L) -> $L", TRANSFORMER_NAME, RESPONSE_NAME, transformRecord(RESPONSE_NAME, target.getTypeName(), "", object.hasJavaRecordReference()))
                : empty();

        if (!hasPagination) {
            return dataBlock.add("\n$L,\n$L\n", queryFunction, transformFunction).unindent().unindent().addStatement(")").build();
        }

        var filteredInputs = allQueryInputs
                .stream()
                .filter(it -> !it.equals(PAGE_SIZE_NAME) && !it.equals(PAGINATION_AFTER.getName()) && !it.equals(SELECTION_SET_NAME) &&
                        target.getOrderField().map(AbstractField::getName).map(orderByField -> !orderByField.equals(it)).orElse(true))
                .collect(Collectors.joining(", "));
        var inputsWithId = isRoot ? filteredInputs : (filteredInputs.isEmpty() ? IDS_NAME : IDS_NAME + ", " + filteredInputs);
        var countFunction = countDBFunction(objectToCall, queryMethodName, inputsWithId);
        return dataBlock
                .add("$N, $L,\n$L,\n$L,\n$L,\n$L\n", PAGE_SIZE_NAME, GeneratorConfig.getMaxAllowedPageSize(), queryFunction, countFunction, getIDFunction(target), transformFunction)
                .unindent()
                .unindent()
                .addStatement(")")
                .build();
    }

    /**
     * @return CodeBlock consisting of a function for a getId call.
     */
    @NotNull
    public CodeBlock getIDFunction(ObjectField referenceField) {
        return referenceField
                .getOrderField()
                .map(orderInputField -> {
                    var objectNode = processedSchema.getObjectOrConnectionNode(referenceField);

                    var orderByFieldMapEntries = processedSchema.getOrderByFieldEnum(orderInputField)
                            .getFields()
                            .stream()
                            .map(orderByField -> CodeBlock.of("$S, $L -> $L",
                                    orderByField.getName(), TYPE_NAME, createJoinedGetFieldAsStringCallBlock(orderByField, objectNode)))
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

    private CodeBlock createJoinedGetFieldAsStringCallBlock(OrderByEnumField orderByField, ObjectDefinition objectNode) {
        return orderByField.getSchemaFieldsWithPathForIndex(processedSchema, objectNode)
                .entrySet()
                .stream()
                .map(fieldWithPath -> createNullSafeGetFieldAsStringCall(fieldWithPath.getKey(), fieldWithPath.getValue()))
                .collect(CodeBlock.joining(" + \",\" + "));
    }

    private CodeBlock createNullSafeGetFieldAsStringCall(ObjectField field,  List<String> path) {
        var getFieldCall = field.getMappingFromSchemaName().asGetCall();
        var fullCallBlock = CodeBlock.of("$L$L$L", TYPE_NAME, path.stream().map(it -> new MethodMapping(it).asGetCall()).collect(CodeBlock.joining("")), getFieldCall);

        if (field.getTypeClass() != null &&
                (field.getTypeClass().isPrimitive() || field.getTypeClass().equals(STRING.className))) {
            return fullCallBlock;
        }
        return CodeBlock.of("$L == null ? null : $L.toString()", fullCallBlock, fullCallBlock);
    }

    @NotNull
    private CodeBlock getSimpleRootDBCall(ObjectField target, String methodName, String queryLocation, String inputString) {
        return CodeBlock
                .builder()
                .add(declareTransform())
                .add(declare(SELECTION_SET_NAME, asMethodCall(TRANSFORMER_NAME, METHOD_SELECT_NAME)))
                .add(
                        declare(
                                methodName,
                                CodeBlock.of(
                                        "$N.$L($L$N)",
                                        uncapitalize(queryLocation),
                                        uncapitalize(methodName),
                                        inputString.isEmpty() ? empty() : CodeBlock.of("$L, ", inputString),
                                        SELECTION_SET_NAME
                                )
                        )
                )
                .add(generateSchemaOutputs(target))
                .build();
    }

    /**
     * @return CodeBlock for declaring the transformer class and calling it on each record input.
     */
    @NotNull
    protected CodeBlock transformInputs(List<? extends InputField> specInputs) {
        if (specInputs.stream().filter(processedSchema::isInputType).map(processedSchema::getInputType).anyMatch(RecordObjectDefinition::hasTable)) {
            return empty();
        }

        var code = CodeBlock.builder();
        var recordCode = CodeBlock.builder();

        var inputObjects = specInputs.stream().filter(processedSchema::isInputType).collect(Collectors.toList());
        for (var in : inputObjects) {
            code.add(declareRecords(in, 0));
            recordCode.add(unwrapRecords(MapperContext.createResolverContext(in, true, processedSchema)));
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

    protected CodeBlock declareRecords(InputField target, int recursion) {
        recursionCheck(recursion);

        var input = processedSchema.getInputType(target);
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
                .filter(processedSchema::isInputType)
                .forEach(in -> code.add(declareRecords(in, recursion + 1)));

        return code.build();
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    @NotNull
    private CodeBlock unwrapRecords(MapperContext context) {
        if (context.hasJavaRecordReference()) {
            return empty();
        }

        var containedInputTypes = context.getTargetType()
                .getInputsSortedByNullability()
                .stream()
                .filter(processedSchema::isInputType)
                .filter(it -> processedSchema.isTableInputType(it) || processedSchema.isJavaRecordType(it) || processedSchema.getInputType(it).getFields().stream().anyMatch(processedSchema::isInputType))
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
        if (fieldCode.isEmpty() || fields.stream().noneMatch(processedSchema::isInputType)) {
            return code.build();
        }

        if (context.isIterable() && !(context.hasTable() && fields.stream().anyMatch(it -> it.isIterableWrapped() && processedSchema.isInputType(it)))) {
            return code.build();
        }

        return wrapNotNull(sourceName, code.add(context.wrapFields(fieldCode.build())).build());
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    protected CodeBlock generateSchemaOutputs(ObjectField target) {
        var code = CodeBlock.builder();
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return code.build();
        }

        return code
                .add(generateSchemaOutputs(MapperContext.createResolverContext(target, false, processedSchema)))
                .add(returnCompletedFuture(getResolverResultName(target)))
                .build();
    }

    /**
     * @return This field's name formatted as a method call result.
     */
    @NotNull
    protected String getResolverResultName(ObjectField target) {
        if (!processedSchema.isObject(target)) {
            return asResultName(target.getUnprocessedFieldOverrideInput());
        }

        return asListedNameIf(target.getTypeName(), target.isIterableWrapped());
    }

    /**
     * @return Code for adding error types and calling transform methods.
     */
    protected CodeBlock generateSchemaOutputs(MapperContext mapperContext) {
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
                innerCode.add(innerContext.getSetMappingBlock(asListedName(processedSchema.getErrorTypeDefinition(innerField.getTypeName()).getName())));
            } else if (!innerField.isExplicitlyNotGenerated() && !innerContext.getPreviousContext().hasRecordReference()) {
                if (!innerContext.targetIsType()) {
                    innerCode.add(innerContext.getSetMappingBlock(getFieldSetContent((ObjectField) innerField, (ObjectField) previousTarget)));
                } else if (innerContext.shouldUseStandardRecordFetch()) {
                    innerCode.add(innerContext.getRecordSetMappingBlock(previousTarget.getName()));
                } else if (innerContext.hasRecordReference()) {
                    innerCode.add(innerContext.getSetMappingBlock(createIdFetch(innerField, previousTarget.getName(), innerContext.getPath(), true))); // TODO: Should be done outside for? Preferably devise some general dataloader-like solution applying to query classes.
                } else {
                    innerCode.add(generateSchemaOutputs(innerContext));
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

    @NotNull
    private CodeBlock getFieldSetContent(ObjectField field, ObjectField previousField) {
        var resultName = asIterableResultNameIf(previousField.getUnprocessedFieldOverrideInput(), previousField.isIterableWrapped());

        var returnIsMappable = service.returnsJavaRecord() || service.getReturnType().getName().endsWith(RECORD_NAME_SUFFIX);
        if (processedSchema.isObject(previousField) && returnIsMappable) {
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

    @Override
    public List<MethodSpec> generateAll() {
        return ((ObjectDefinition) getLocalObject())
                .getFieldsReferringTo(processedSchema.getNamesWithTableOrConnections())
                .stream()
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(GenerationField::hasServiceReference)
                .map(this::generate)
                .filter(it -> !it.code.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject()
                .getFieldsReferringTo(processedSchema.getNamesWithTableOrConnections())
                .stream()
                .filter(GenerationField::hasServiceReference);
        return getLocalObject().isRoot()
                ? fieldStream.allMatch(GenerationField::isGeneratedWithResolver)
                : fieldStream.filter(GenerationField::hasServiceReference).allMatch(f -> !f.isResolver() || f.isGeneratedWithResolver());
    }
}
