package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.mapping.Alias;
import no.sikt.graphitron.definitions.mapping.AliasWrapper;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.*;
import no.sikt.graphql.helpers.query.AfterTokenWithTypeName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jooq.Field;
import org.jooq.SelectLimitPercentStep;
import org.jooq.SelectSeekStepN;

import javax.lang.model.element.Modifier;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.GeneratorConfig.optionalSelectIsEnabled;
import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.*;
import static no.sikt.graphitron.generators.db.FetchSingleTableInterfaceDBMethodGenerator.TOKEN;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.SELECT_JOIN_STEP;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessageAndThrow;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

public class FetchMultiTableDBMethodGenerator extends FetchDBMethodGenerator {
    public static final String
            UNION_KEYS_QUERY = internalPrefix("unionKeysQuery"),
            VAR_REPRESENTATIONS = inputPrefix(FEDERATION_REPRESENTATIONS_ARGUMENT.getName()),
            VAR_FILTERED_REPRESENTATIONS = VAR_REPRESENTATIONS + "_filtered";
    public static final String TYPE_FIELD = "$type";
    public static final String DATA_FIELD = "$data";
    public static final String PK_FIELDS = "$pkFields";
    public static final String INNER_ROW_NUM = "$innerRowNum";
    private static final String ELEMENT_FIRST = ELEMENT_NAME + "0";
    public static final String MSG_ERROR_NO_TABLE = "Type(s) '%s' are used in a query returning multitable interface or union '%s', but do not have tables set. This is not supported.";
    private FetchContext initialContext;

    public FetchMultiTableDBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema
    ) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var inputParser = new InputParser(target, processedSchema);

        var unionOrInterfaceDefinition = processedSchema.isUnion(target) ? processedSchema.getUnion(target) : processedSchema.getInterface(target);

        // Order is important for paginated queries as it gets data fields by index in the mapping
        LinkedHashSet<ObjectDefinition> implementations = new LinkedHashSet<>(processedSchema.getTypesFromInterfaceOrUnion(unionOrInterfaceDefinition.getName()));

        return getSpecBuilder(target, unionOrInterfaceDefinition.getGraphClassName(), inputParser)
                .addCode(implementations.isEmpty() ? returnWrap("null") : getCode(target, implementations, inputParser.getMethodInputNames(false, false, true)))
                .build();
    }

    @Override
    protected CodeBlock getHelperMethodCallForNestedField(ObjectField field, FetchContext context) {
        return null;
    }

    private CodeBlock getCode(ObjectField target, LinkedHashSet<ObjectDefinition> implementations, List<String> inputs) {
        List<String> sortFieldQueryMethodCalls = new ArrayList<>();
        LinkedHashMap<String, String> mappedQueryVariables = new LinkedHashMap<>();
        var joins = CodeBlock.builder();
        var mappedDeclarationBlock = CodeBlock.builder();

        if (target.isResolver()) {
            initialContext = implementations
                    .stream()
                    .findFirst()
                    .map(it -> new FetchContext(processedSchema, new VirtualSourceField(it, target), localObject, false))
                    .orElse(null);
        }

        var typesMissingTable = implementations
                .stream()
                .filter(it -> !it.hasTable())
                .map(AbstractObjectDefinition::getName)
                .collect(Collectors.joining("', '"));
        if (!typesMissingTable.isEmpty()) {
            addErrorMessageAndThrow(MSG_ERROR_NO_TABLE, typesMissingTable, target.getTypeName());
        }

        for (var implementation : implementations) {
            String typeName = implementation.getName();
            sortFieldQueryMethodCalls.add(getSortFieldsMethodName(target, implementation));
            String mappedVariableName = joinStepPrefix(typeName);
            mappedQueryVariables.put(typeName, mappedVariableName);

            var input = new ArrayList<String>();
            if (shouldMakeNodeStrategy()) input.add(VAR_NODE_STRATEGY);
            if (optionalSelectIsEnabled()) input.add(VAR_SELECT);

            mappedDeclarationBlock.declare(
                    mappedVariableName,
                    "$N($L)",
                    getMappedMethodName(target, implementation),
                    String.join(", ", input)
            );

            joins.add("\n.leftJoin($N)\n.on($N.field($S, $T.class).eq($N.field($S, $T.class)))",
                    mappedVariableName,
                    UNION_KEYS_QUERY, PK_FIELDS, JSONB.className,
                    mappedVariableName, PK_FIELDS, JSONB.className);
        }

        var unionQuery = getUnionQuery(sortFieldQueryMethodCalls, inputs, target.hasPagination());
        var multitableQuery = CodeBlock.builder()
                .addIf(target.isResolver(), "$T", DSL.className)
                .addIf(!target.isResolver(), "$N", VAR_CONTEXT)
                .add(".select(")
                .add(indentIfMultiline(wrapRow(createSelectBlock(mappedQueryVariables))))
                .add(".mapping($L)", createMappingContent(target, implementations, target.hasPagination()))
                .add(")\n.from($L)", UNION_KEYS_QUERY)
                .add(joins.build())
                .addIf(processedSchema.returnsList(target), "\n.orderBy($N.field($S), $N.field($S))", UNION_KEYS_QUERY, TYPE_FIELD, UNION_KEYS_QUERY, INNER_ROW_NUM)
                .addIf(target.hasPagination(), "\n.limit($N + 1)\n", VAR_PAGE_SIZE)
                .addIf(!processedSchema.returnsList(target), "\n.limit(2)\n") // So that we get a DataFetchingException on multiple rows, without fetching more than necessary
                .build();

        if (target.isResolver()) {
            multitableQuery = processedSchema.returnsList(target)
                    ? wrapInMultiset(multitableQuery)
                    : wrapInField(multitableQuery);
        }

        return CodeBlock.builder()
                .declareIf(target.hasPagination(), TOKEN, () -> getTokenVariableDeclaration(implementations))
                .addIf(target.isResolver(), () -> createAliasDeclarations(initialContext.getAliasSet()))
                .declare(UNION_KEYS_QUERY, unionQuery)
                .add("\n")
                .add(mappedDeclarationBlock.build())
                .add("\nreturn ")
                .indent()
                .addIf(target.isResolver(), "$N.select(\n", VAR_CONTEXT)
                .indent()
                .addIf(target.isResolver(), () -> getInitialKey(initialContext))
                .add(multitableQuery)
                .unindent()
                .addIf(target.isResolver(), "\n)")
                .addIf(target.isResolver(), () -> CodeBlock.of("\n.from($L)\n", initialContext.renderQuerySource(getLocalTable())))
                .addIf(target.isResolver(), () -> formatWhereContents(initialContext, resolverKeyParamName, isRoot, target.isResolver()))
                .add(getFetchCodeBlock(target))
                .unindent()
                .build();
    }

    private static @NotNull CodeBlock getTokenVariableDeclaration(Set<ObjectDefinition> implementations) {
        var code = CodeBlock.builder()
                .add("$T.getOrderByValuesForMultitableInterface($N, \n$T.of(", QUERY_HELPER.className, VAR_CONTEXT, MAP.className)
                .indent();

        var mapEntries = implementations.stream()
                .map(implementation ->
                        CodeBlock.of("\n$S, $L", implementation.getName(), getPrimaryKeyFieldsWithTableAliasBlock(implementation.getTable().getName())))
                .toList();

        return code
                .add(CodeBlock.join(mapEntries, ","))
                .unindent()
                .add("\n),\n$N)", inputPrefix(PAGINATION_AFTER.getName()))
                .build();
    }

    private @NotNull CodeBlock getUnionQuery(List<String> subselectVariableNames, List<String> inputNames, boolean isConnection) {
        var additionalInputs = new LinkedHashSet<String>();

        Optional.ofNullable(initialContext)
                .map(FetchContext::getAliasSet)
                .stream()
                .findFirst()
                .ifPresent(it -> it.stream().map(AliasWrapper::getAlias).map(Alias::getMappingName).forEach(additionalInputs::add));

        if (shouldMakeNodeStrategy()) {
            additionalInputs.add(VAR_NODE_STRATEGY);
        }

        if (isConnection) {
            additionalInputs.add(VAR_PAGE_SIZE);
            additionalInputs.add(TOKEN);
        }

        var inputs = Stream.concat(
                        additionalInputs.stream(),
                        inputNames.stream())
                .map(CodeBlock::of).collect(CodeBlock.joining(", "));

        return CodeBlock.of(
                subselectVariableNames.stream()
                        .reduce("", (currString, element) ->
                                currString.isEmpty() ? String.format("%s(%s)", element, inputs)
                                        : String.format("%s(%s)\n.unionAll(%s)", element, inputs, currString))
        );
    }

    private CodeBlock createSelectBlock(Map<String, String> mappedQueryVariables) {
        return Stream.concat(
                Stream.of(CodeBlock.of("$N.field($S, $T.class)", UNION_KEYS_QUERY, TYPE_FIELD, STRING.className)),
                mappedQueryVariables.values().stream().map(it -> CodeBlock.of("$N.field($S)", it, DATA_FIELD))
        ).collect(CodeBlock.joining(",\n"));
    }

    private CodeBlock getFetchCodeBlock(ObjectField target) {
        var isList = processedSchema.returnsList(target);
        if (target.isResolver()) {
            var valuesBlock = CodeBlock.of("$1L -> $1N.value1().valuesRow(),\n", VAR_RECORD_ITERATOR);
            return CodeBlock
                    .builder()
                    .add(".fetchMap(")
                    .addIf(!isList, indentIfMultiline(CodeBlock.of("$L$T::value2", valuesBlock, RECORD2.className)))
                    .addIf(isList, indentIfMultiline(CodeBlock.of("$1L$2L -> $2L.value2().map($3T::value1)", valuesBlock, VAR_RECORD_ITERATOR, RECORD1.className)))
                    .addStatement(")")
                    .build();
        }

        return CodeBlock.statementOf(".$L($T::value1)",
                isList ? "fetch" : "fetchOne",
                RECORD1.className);
    }

    private CodeBlock createMappingContent(GenerationSourceField<?> target, LinkedHashSet<ObjectDefinition> implementations, boolean isConnection) {
        var interfaceClassName = processedSchema.getRecordType(target).getGraphClassName();
        var lambdaParameters = new LinkedHashMap<String, String>();

        var index = 1;
        for (ObjectDefinition implementation : implementations) {
            lambdaParameters.put(implementation.getName(), ELEMENT_NAME + index);
            index++;
        }

        var code = CodeBlock.builder()
                .indent()
                .add("($L, $L) -> ", ELEMENT_FIRST, lambdaParameters.values().stream().map(CodeBlock::of).collect(CodeBlock.joining(", ")))
                .beginControlFlowIf(isConnection)
                .addIf(isConnection, "$T $N = ", RECORD2.className, VAR_RESULT)
                .beginControlFlow("switch ($N)", ELEMENT_FIRST);

        var classToCastTo = isConnection ? RECORD2.className : interfaceClassName;

        implementations.forEach(implementation ->
                code.addStatement("case $S -> ($T) $N", implementation.getName(), classToCastTo, lambdaParameters.get(implementation.getName()))
        );

        return code.add("default -> \n")
                .indent()
                .add(
                        "throw new $T($T.format($S, \"$T\", $N));\n",
                        RuntimeException.class,
                        STRING.className,
                        "Querying multitable interface/union '%s' returned unexpected typeName '%s'",
                        interfaceClassName,
                        ELEMENT_FIRST
                )
                .unindent()
                .endControlFlowIf(!isConnection)
                .endControlFlowAsStatementIf(isConnection)
                .addStatementIf(isConnection, "return $T.of($N.get(0, $T.class), $N.get(1, $T.class))",
                        PAIR.className, VAR_RESULT, STRING.className, VAR_RESULT, interfaceClassName)
                .endControlFlowIf(isConnection)
                .unindent()
                .build();
    }

    public List<MethodSpec> generateWithSubselectMethods(ObjectField target) {
        var mainMethod = generate(target);
        var methodInputs = new InputParser(target, processedSchema).getMethodParameterSpecs(false, false, true);
        var unionOrInterfaceDefinition = processedSchema.isUnion(target)
                ? processedSchema.getUnion(target)
                : processedSchema.getInterface(target);

        return Stream.concat(
                Stream.of(mainMethod),
                processedSchema
                        .getTypesFromInterfaceOrUnion(unionOrInterfaceDefinition.getName())
                        .stream()
                        .map(it -> getMethodsForImplementation(target, it, methodInputs))
                        .flatMap(Collection::stream)
        ).toList();
    }

    private List<MethodSpec> getMethodsForImplementation(ObjectField target, ObjectDefinition implementation, List<ParameterSpec> methodInputs) {
        var virtualTarget = new VirtualSourceField(implementation, target);
        var context = new FetchContext(processedSchema, virtualTarget, localObject, true);
        var refContext = virtualTarget.isResolver() ? context.nextContext(virtualTarget) : context;

        return List.of(getSortFieldsMethod(target, implementation, refContext, methodInputs), getMappedMethod(target, implementation));
    }

    private MethodSpec getSortFieldsMethod(ObjectField target, ObjectDefinition implementation, FetchContext context, List<ParameterSpec> methodInputs) {
        var methodBuilder = MethodSpec
                .methodBuilder(getSortFieldsMethodName(target, implementation))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(getReturnTypeForKeysMethod(target.hasPagination(), !processedSchema.returnsList(target)))
                .addCode(getSortFieldsMethodCode(implementation, context, target));

        if (target.isResolver()) {
            Optional.ofNullable(initialContext)
                    .flatMap(it -> it.getAliasSet().stream().findFirst())
                    .ifPresent(startAlias ->
                            methodBuilder.addParameter(
                                    getTableClass(startAlias.getAlias().getTable().getName()).orElseThrow(),
                                    aliasPrefix(startAlias.getAlias().getMappingName())
                            )
                    );
        }
        return methodBuilder
                .addParameterIf(shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, VAR_NODE_STRATEGY)
                .addParameterIf(target.hasPagination(), Integer.class, VAR_PAGE_SIZE)
                .addParameterIf(target.hasPagination(), AfterTokenWithTypeName.class, TOKEN)
                .addParameters(methodInputs)
                .build();
    }

    private MethodSpec getMappedMethod(ObjectField target, ObjectDefinition implementation) {
        var mappedContext = new FetchContext(processedSchema, new VirtualSourceField(implementation), processedSchema.getQueryType(), false);
        return MethodSpec
                .methodBuilder(getMappedMethodName(target, implementation))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(target.hasPagination() ?
                        getReturnTypeForMappedConnectionMethod(implementation.getGraphClassName())
                        : getReturnTypeForMappedMethod(implementation.getGraphClassName()))
                .addCode(getMappedMethodCode(target, implementation, mappedContext))
                .addParameterIf(shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, VAR_NODE_STRATEGY)
                .addParameterIf(optionalSelectIsEnabled(), SELECTION_SET.className, VAR_SELECT)
                .build();
    }

    private CodeBlock getMappedMethodCode(ObjectField target, ObjectDefinition implementation, FetchContext context) {
        var code = CodeBlock.builder();
        var querySource = context.renderQuerySource(getLocalTable());
        var selectCode = generateSelectRow(context);

        return code
                .add(createAliasDeclarations(context.getAliasSet()))
                .add("return $T.select(\n", DSL.className)
                .indent()
                .add(getPrimaryKeyFieldsArray(implementation.getName(), querySource.toString(), context.getTargetTable().getName()))
                .add(".as($S),\n$T.field(\n", PK_FIELDS, DSL.className)
                .indent()
                .addIf(target.hasPagination(), "$1T.select(\n$1T.row(\n", DSL.className)
                .addIf(target.hasPagination(), () ->
                        CodeBlock.of("$T.getOrderByTokenForMultitableInterface($L, $L, $S),\n",
                                QUERY_HELPER.className, context.getTargetAlias(), getPrimaryKeyFieldsWithTableAliasBlock(context.getTargetAlias()), implementation.getName()))
                .add("$T.select($L)", DSL.className, indentIfMultiline(selectCode))
                .unindent()
                .addIf(target.hasPagination(), ")\n)\n")
                .add("\n).as($S))", DATA_FIELD)
                .add("\n.from($L);", querySource)
                .unindent()
                .build();
    }

    private CodeBlock getSortFieldsMethodCode(ObjectDefinition implementation, FetchContext context, ObjectField queryTarget) {
        var targetAlias = context.getTargetAlias();
        var isEntities = getLocalObject().isOperationRoot() && queryTarget.getName().equals(FEDERATION_ENTITIES_FIELD.getName()) && processedSchema.isFederationImported();
        var whereBlock = !isEntities ? formatWhereContents(context, resolverKeyParamName, isRoot, false) : formatEntitiesWhere(context, implementation);
        String implName = implementation.getName();

        // When first reference step is a condition reference without key, we need to join explicitly
        // Otherwise, the first join is implicit via the jOOQ key path and can be skipped for cleaner code.
        var firstReferenceStep = context.getReferenceObjectField().getFieldReferences().stream().findFirst();
        var hasConditionReferenceAsFirstStep = queryTarget.isResolver()
                && firstReferenceStep.map(it -> it.hasTableCondition() && !it.hasKey()).orElse(false);

        boolean returnsList = processedSchema.returnsList(queryTarget);

        return CodeBlock.builder()
                // Skip first alias declaration for resolvers - it's already declared in main method and passed to helpers
                .add(createAliasDeclarations(context.getAliasSet(), queryTarget.isResolver()))
                .declareIf(returnsList, VAR_ORDER_FIELDS, getPrimaryKeyFieldsWithTableAliasBlock(targetAlias))
                .declareIf(isEntities, VAR_FILTERED_REPRESENTATIONS, getFilteredEntities(implName))
                .add("return $T.select(\n", DSL.className)
                .indent()
                .indent()
                .indent()
                .indent()
                .add("$T.inline($S).as($S)", DSL.className, implName, TYPE_FIELD)
                .addIf(returnsList,
                        ",\n$T.rowNumber().over($T.orderBy($L)).as($S)", DSL.className, DSL.className, VAR_ORDER_FIELDS, INNER_ROW_NUM)
                .add(",\n")
                .add(getPrimaryKeyFieldsArray(implName, targetAlias, context.getTargetTable().getName()))
                .add(".as($S)", PK_FIELDS)
                .unindent()
                .unindent()
                .add("\n)\n.from($N)\n", context.getSourceAlias())
                .add(createSelectJoins(context.getJoinSet(), !hasConditionReferenceAsFirstStep))
                .add(whereBlock)
                .add("\n")
                .add(createSelectConditions(context.getConditionList(), !whereBlock.isEmpty()))
                .addIf(
                        queryTarget.hasPagination(),
                        ".$1L($2N == null ? $3L : $4T.inline($5S).greaterOrEqual($2N.typeName()))\n" +
                                ".and($2N != null && $2N.matches($5S) ? $4T.row($6L).gt($4T.row($2N.fields())) : $3L)",
                        whereBlock.isEmpty() ? "where" : "and",
                        TOKEN,
                        noCondition(),
                        DSL.className,
                        implementation.getName(),
                        getPrimaryKeyFieldsWithTableAliasBlock(targetAlias)
                )
                .addIf(returnsList, ".orderBy($L)", VAR_ORDER_FIELDS)
                .addIf(queryTarget.hasPagination(), "\n.limit($N + 1)", VAR_PAGE_SIZE)
                .addIf(!returnsList, "\n.limit(2)") // So that we get a DataFetchingException on multiple rows, without fetching more than necessary
                .addStatement("")
                .unindent()
                .unindent()
                .build();
    }

    /**
     * @return Formatted CodeBlock for the where-statement and surrounding code for federation queries.
     */
    protected CodeBlock formatEntitiesWhere(FetchContext context, ObjectDefinition implementation) {
        var type = processedSchema.getObject(context.getReferenceObjectField());
        var conditionBlocks = new ArrayList<CodeBlock>();
        for (var k: type.getEntityKeys().keys()) {
            var containedKeys = k.getKeys();
            var code = CodeBlock.builder();
            if (containedKeys.size() < 2) {
                var key = containedKeys.get(0);
                code.add(getEntitiesConditionCode(context, key, type.getFieldByName(key), implementation));
            } else {
                var conditions = new ArrayList<CodeBlock>();
                for (var key : containedKeys) {
                    conditions.add(getEntitiesConditionCode(context, key, type.getFieldByName(key), implementation));
                }
                code.add("$T.and($L)", DSL.className, indentIfMultiline(CodeBlock.join(conditions, ",\n")));
            }
            conditionBlocks.add(code.build());
            // var nestedKeys = k.getNestedKeys();  # TODO: Support nested keys.
        }
        return formatJooqConditions(conditionBlocks, "or");
    }

    private CodeBlock getFilteredEntities(String implementationName) {
        return CodeBlock
                .builder()
                .add("$N\n", VAR_REPRESENTATIONS)
                .add(".stream()\n")
                .add(".filter($T::nonNull)\n", OBJECTS.className)
                .add(".filter($L -> $S.equals($N.get($S)))\n", VAR_ITERATOR, implementationName, VAR_ITERATOR, TYPE_NAME.getName())
                .add(collectToList())
                .build();
    }

    private CodeBlock getEntitiesConditionCode(FetchContext context, String key, ObjectField field, ObjectDefinition implementation) {
        var type = field.isID()
                ? STRING.className
                : getFieldType(context.getTargetTable().getName(), field.getUpperCaseName()).map(ClassName::get).orElse(STRING.className);
        var streamBlock = CodeBlock.of(
                "$N.stream().map($L -> ($T) $N.get($S))",
                VAR_FILTERED_REPRESENTATIONS,
                VAR_ITERATOR,
                type,
                VAR_ITERATOR,
                key
        );
        if (processedSchema.isNodeIdField(field)) {
            var code = CodeBlock.of("$L.filter($T::nonNull)$L", streamBlock, OBJECTS.className, collectToList());
            return hasIdOrIdsBlock(code, implementation, context.getTargetAlias(), CodeBlock.empty(), true);
        }

        return CodeBlock
                .builder()
                .add("$N.", context.getTargetAlias())
                .addIf(field.isID(), "hasIds(")
                .addIf(
                        !field.isID(),
                        "$L$L.in(",
                        field.getUpperCaseName(),
                        toJOOQEnumConverter(field.getTypeName(), processedSchema)
                )
                .add(streamBlock)
                .add(collectToList())
                .add(")")
                .build();
    }

    private static CodeBlock getPrimaryKeyFieldsArray(String name, String alias, String tableName) {
        return CodeBlock.of("$T.jsonbArray($T.inline($S), $L)", DSL.className, DSL.className, name, getPrimaryKeyFields(tableName, alias));
    }

    private static CodeBlock getPrimaryKeyFields(String tableName, String alias) {
        var code = CodeBlock.builder();

        getPrimaryKeyForTable(tableName)
                .map(pk -> pk
                        .getFields()
                        .stream().map(Field::getName)
                        .map(it -> CodeBlock.of("$N.$L", alias, it.toUpperCase()))
                        .toList()
                ).ifPresent(it -> code.add(CodeBlock.join(it, ", ")));

        return code.build();
    }

    private static @NotNull String getMappedMethodName(ObjectField target, ObjectDefinition implementation) {
        return String.format("%sFor%s", implementation.getName().toLowerCase(), StringUtils.capitalize(target.getName()));
    }

    private static @NotNull String getSortFieldsMethodName(ObjectField target, ObjectDefinition implementation) {
        return String.format("%sSortFieldsFor%s", implementation.getName().toLowerCase(), StringUtils.capitalize(target.getName()));
    }

    private static ParameterizedTypeName getReturnTypeForMappedMethod(ClassName implementationClassName) {
        return ParameterizedTypeName.get(SELECT_JOIN_STEP.className,
                ParameterizedTypeName.get(RECORD2.className,
                        JSONB.className,
                        implementationClassName)
        );
    }

    private static ParameterizedTypeName getReturnTypeForMappedConnectionMethod(ClassName implementationClassName) {
        return ParameterizedTypeName.get(SELECT_JOIN_STEP.className,
                ParameterizedTypeName.get(RECORD2.className,
                        JSONB.className,
                        ParameterizedTypeName.get(RECORD2.className,
                                ParameterizedTypeName.get(SELECT_FIELD.className, STRING.className),
                                ParameterizedTypeName.get(SELECT_SELECT_STEP.className,
                                        ParameterizedTypeName.get(RECORD1.className,
                                                implementationClassName
                                        )
                                )
                        )
                )
        );
    }

    private static ParameterizedTypeName getReturnTypeForKeysMethod(boolean isConnection, boolean returnsSingleObject) {
        if (returnsSingleObject) {
            return ParameterizedTypeName.get(
                    ClassName.get(SelectLimitPercentStep.class),
                    ParameterizedTypeName.get(RECORD2.className,
                            STRING.className,
                            JSONB.className)
            );
        }

        return ParameterizedTypeName.get(
                ClassName.get(isConnection ? SelectLimitPercentStep.class : SelectSeekStepN.class),
                ParameterizedTypeName.get(RECORD3.className,
                        STRING.className,
                        INTEGER.className,
                        JSONB.className)
        );
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .filter(processedSchema::isMultiTableField)
                .filter(it -> !it.getTypeName().equals(NODE_TYPE.getName()))
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .flatMap(it -> generateWithSubselectMethods(it).stream())
                .filter(it -> !it.code().isEmpty())
                .toList();
    }
}
