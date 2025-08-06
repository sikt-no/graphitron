package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.mapping.Alias;
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

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.getPrimaryKeyFieldsWithTableAliasBlock;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.indentIfMultiline;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.db.FetchSingleTableInterfaceDBMethodGenerator.TOKEN;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.getPrimaryKeyForTable;
import static no.sikt.graphitron.mappings.TableReflection.getTableClass;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

public class FetchMultiTableDBMethodGenerator extends FetchDBMethodGenerator {

    public static final String UNION_KEYS_QUERY = "unionKeysQuery";
    public static final String TYPE_FIELD = "$type";
    public static final String DATA_FIELD = "$data";
    public static final String PK_FIELDS = "$pkFields";
    public static final String INNER_ROW_NUM = "$innerRowNum";
    public static final int MAPPED_START_INDEX_IN_SELECT = 1;
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
                .addCode(implementations.isEmpty() ? CodeBlock.of("return null;") : getCode(target, implementations, inputParser.getMethodInputs().keySet()))
                .build();
    }

    private CodeBlock getCode(ObjectField target, LinkedHashSet<ObjectDefinition> implementations, Set<String> inputs) {
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

        for (var implementation : implementations) {
            if (!implementation.hasTable()) {
                throw new IllegalArgumentException(String.format("Type '%s' is returned in an interface query, but not have table set. This is not supported.", implementation.getName()));
            }
            String typeName = implementation.getName();
            sortFieldQueryMethodCalls.add(getSortFieldsMethodName(target, implementation));
            String mappedVariableName = "mapped" + typeName;
            mappedQueryVariables.put(typeName, mappedVariableName);
            mappedDeclarationBlock.declare(
                    mappedVariableName,
                    "$N($L)",
                    getMappedMethodName(target, implementation),
                    CodeBlock.ofIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY_NAME)
            );

            joins.add("\n.leftJoin($N)\n.on($N.field($S, $T.class).eq($N.field($S, $T.class)))",
                    mappedVariableName,
                    UNION_KEYS_QUERY, PK_FIELDS, JSONB.className,
                    mappedVariableName, PK_FIELDS, JSONB.className);
        }

        var unionQuery = getUnionQuery(sortFieldQueryMethodCalls, inputs, target.hasForwardPagination());
        var returnsList = target.isIterableWrapped() || target.hasForwardPagination();

        var multitableQuery = CodeBlock.builder()
                .addIf(target.isResolver(), "$T", DSL.className)
                .addIf(!target.isResolver(), "$N", CONTEXT_NAME)
                .add(".select($L)", createSelectBlock(mappedQueryVariables, !target.hasForwardPagination()))
                .add("\n.from($L)", UNION_KEYS_QUERY)
                .add(joins.build())
                .add("\n.orderBy($N.field($S), $N.field($S))", UNION_KEYS_QUERY, TYPE_FIELD, UNION_KEYS_QUERY, INNER_ROW_NUM)
                .addIf(target.hasForwardPagination(), "\n.limit($N + 1)\n", PAGE_SIZE_NAME)
                .build();

        return CodeBlock.builder()
                .declareIf(target.hasForwardPagination(), TOKEN, () -> getTokenVariableDeclaration(implementations))
                .addIf(target.isResolver(), () -> createAliasDeclarations(initialContext.getAliasSet()))
                .declare(UNION_KEYS_QUERY, unionQuery)
                .add("\n")
                .add(mappedDeclarationBlock.build())
                .add("\n")
                .addIf(returnsList, "return ")
                .addIf(!returnsList, "var $N = ", VARIABLE_RESULT)
                .indent()
                .addIf(target.isResolver(), "$N.select(\n", CONTEXT_NAME)
                .indent()
                .addIf(target.isResolver(), () -> getInitialKey(initialContext))
                .add(target.isResolver() ? wrapInMultiset(multitableQuery) : multitableQuery)
                .unindent()
                .addIf(target.isResolver(), "\n)")
                .addIf(target.isResolver(), () -> CodeBlock.of("\n.from($L)", initialContext.renderQuerySource(getLocalTable())))
                .addIf(target.isResolver(), () -> formatWhereContents(initialContext, resolverKeyParamName, isRoot, target.isResolver()))
                .add(createFetchAndMapping(target, implementations))
                .build();
    }

    private static @NotNull CodeBlock getTokenVariableDeclaration(Set<ObjectDefinition> implementations) {
        var code = CodeBlock.builder()
                .add("$T.getOrderByValuesForMultitableInterface($N, \n$T.of(", QUERY_HELPER.className, CONTEXT_NAME, MAP.className)
                .indent();

        var mapEntries = implementations.stream()
                .map(implementation ->
                        CodeBlock.of("\n$S, $L", implementation.getName(), getPrimaryKeyFieldsWithTableAliasBlock(implementation.getTable().getName())))
                .toList();

        return code
                .add(CodeBlock.join(mapEntries, ","))
                .unindent()
                .add("\n),\n$N)", PAGINATION_AFTER.getName())
                .build();
    }

    private @NotNull CodeBlock getUnionQuery(List<String> subselectVariableNames, Set<String> inputNames, boolean isConnection) {
        var additionalInputs = new LinkedHashSet<String>();

        Optional.ofNullable(initialContext)
                .map(FetchContext::getAliasSet)
                .stream()
                .findFirst()
                .ifPresent(it -> it.stream().map(Alias::getMappingName).forEach(additionalInputs::add));

        if (isConnection) {
            additionalInputs.add(PAGE_SIZE_NAME);
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

    private CodeBlock createSelectBlock(Map<String, String> mappedQueryVariables, boolean getFieldByName) {
        var code = CodeBlock.builder()
                .indent()
                .add("\n$N.field($S)", UNION_KEYS_QUERY, TYPE_FIELD);

        if (getFieldByName) {
            mappedQueryVariables.forEach(
                    (key, variableName) -> code.add(",\n$N.field($S).as($S)", variableName, DATA_FIELD, getDataFieldNameForImplementation(key))
            );
        } else {
            mappedQueryVariables.forEach(
                    (key, variableName) -> code.add(",\n$N.field(1)", variableName)
            );
        }

        return code.unindent().build();
    }

    private CodeBlock createFetchAndMapping(ObjectField target, Set<ObjectDefinition> implementations) {
        var graphClassName = processedSchema.getRecordType(target).getGraphClassName();
        var mappingContent = createMappingContent(graphClassName, implementations, target.hasForwardPagination());
        var returnsList = target.isIterableWrapped() || target.hasForwardPagination();

        if (target.isResolver()) {
            return CodeBlock.builder()
                    .add("\n.fetchMap(")
                    .indent()
                    .add("\nr -> r.value1().valuesRow(),")
                    .add("\nr -> r.value2().map($L)\n);", mappingContent)
                    .unindent()
                    .unindent()
                    .build();
        }

        return CodeBlock.builder()
                .addIf(!returnsList, "\n.fetchOne();")
                .addIf(returnsList, "\n.fetch()\n.map(\n$L\n);", mappingContent)
                .unindent()
                .addIf(!returnsList, "\nreturn $L == null ? null : $L.map(\n$L\n);", VARIABLE_RESULT, VARIABLE_RESULT, mappingContent)
                .build();
    }

    private CodeBlock createMappingContent(ClassName interfaceClassName, Set<ObjectDefinition> implementations, boolean isConnection) {
        var code = CodeBlock.builder()
                .indent()
                .beginControlFlow("$N -> ", VARIABLE_INTERNAL_ITERATION)
                .addIf(isConnection, "$T $N;\n", RECORD2.className, VARIABLE_RESULT)
                .beginControlFlow("switch ($N.get(0, $T.class))", VARIABLE_INTERNAL_ITERATION, STRING.className);

        if (isConnection) {
            int i = MAPPED_START_INDEX_IN_SELECT;

            for (var implementation : implementations) {
                code.add("case $S:\n", implementation.getName())
                        .indent()
                        .add("$N = $N.get($L, $T.class); break;\n", VARIABLE_RESULT, VARIABLE_INTERNAL_ITERATION, i, RECORD2.className)
                        .unindent();
                i++;
            }

        } else {
            implementations.forEach(implementation ->
                    code.add("case $S:\n", implementation.getName())
                            .indent()
                            .add("return $N.get($S, $T.class);\n",
                                    VARIABLE_INTERNAL_ITERATION,
                                    getDataFieldNameForImplementation(implementation.getName()),
                                    interfaceClassName)
                            .unindent()
            );
        }

        code.add("default:\n")
                .indent()
                .add("throw new $T($T.format($S, \"$T\", $N.get(0, $T.class)));\n",
                        RuntimeException.class,
                        STRING.className,
                        "Querying interface '%s' returned unexpected typeName '%s'",
                        interfaceClassName,
                        VARIABLE_INTERNAL_ITERATION,
                        STRING.className)
                .unindent()
                .endControlFlow();

        if (isConnection) {
            code.addStatement("return $T.of($N.get(0, $T.class), $N.get(1, $T.class))", PAIR.className, VARIABLE_RESULT, STRING.className, VARIABLE_RESULT, interfaceClassName);
        }

        return code.endControlFlow()
                .unindent()
                .build();
    }

    public List<MethodSpec> generateWithSubselectMethods(ObjectField target) {
        var mainMethod = generate(target);
        var methodInputs = getMethodParameters(new InputParser(target, processedSchema));
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
        var context = new FetchContext(processedSchema, virtualTarget, localObject, false);
        var refContext = virtualTarget.isResolver() ? context.nextContext(virtualTarget) : context;

        return List.of(getSortFieldsMethod(target, implementation, refContext, methodInputs), getMappedMethod(target, implementation));
    }

    private MethodSpec getSortFieldsMethod(ObjectField target, ObjectDefinition implementation, FetchContext context, List<ParameterSpec> methodInputs) {
        var methodBuilder = MethodSpec
                .methodBuilder(getSortFieldsMethodName(target, implementation))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(getReturnTypeForKeysMethod(target.hasForwardPagination()))
                .addCode(getSortFieldsMethodCode(implementation, context, target));

        if (target.isResolver()) {
            Optional.ofNullable(initialContext)
                    .flatMap(it -> it.getAliasSet().stream().findFirst())
                    .ifPresent(startAlias ->
                            methodBuilder.addParameter(
                                    getTableClass(startAlias.getTable().getName()).orElseThrow(),
                                    startAlias.getMappingName()
                            )
                    );
        }
        return methodBuilder
                .addParameterIf(target.hasForwardPagination(), Integer.class, PAGE_SIZE_NAME)
                .addParameterIf(target.hasForwardPagination(), AfterTokenWithTypeName.class, TOKEN)
                .addParameters(methodInputs)
                .build();
    }

    private MethodSpec getMappedMethod(ObjectField target, ObjectDefinition implementation) {
        var mappedContext = new FetchContext(processedSchema, new VirtualSourceField(implementation), processedSchema.getQueryType(), false);
        return MethodSpec
                .methodBuilder(getMappedMethodName(target, implementation))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(target.hasForwardPagination() ?
                        getReturnTypeForMappedConnectionMethod(implementation.getGraphClassName())
                        : getReturnTypeForMappedMethod(implementation.getGraphClassName()))
                .addCode(getMappedMethodCode(target, implementation, mappedContext))
                .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, NODE_ID_STRATEGY_NAME)
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
                .addIf(target.hasForwardPagination(), "$T.row(\n", DSL.className)
                .addIf(target.hasForwardPagination(), () ->
                        CodeBlock.of("$T.getOrderByTokenForMultitableInterface($L, $L, $S),\n",
                                QUERY_HELPER.className, context.getTargetAlias(), getPrimaryKeyFieldsWithTableAliasBlock(context.getTargetAlias()), implementation.getName()))
                .add("$T.select($L)", DSL.className, indentIfMultiline(selectCode))
                .unindent()
                .addIf(target.hasForwardPagination(), ")\n")
                .add("\n).as($S))", DATA_FIELD)
                .add("\n.from($L);", querySource)
                .unindent()
                .build();
    }

    private static String getDataFieldNameForImplementation(String typeName) {
        return String.format("$dataFor%s", typeName);
    }

    private CodeBlock getSortFieldsMethodCode(ObjectDefinition implementation, FetchContext context, ObjectField queryTarget) {
        var isConnection = queryTarget.hasForwardPagination();
        var code = CodeBlock.builder();
        var alias = context.getTargetAlias();
        var whereBlock = formatWhereContents(context, resolverKeyParamName, isRoot, false);
        String implName = implementation.getName();

        var aliasesExcludingStartAlias = Optional.ofNullable(initialContext)
                .flatMap(it -> it.getAliasSet().stream().findFirst())
                .map(startAlias -> context.getAliasSet().stream().filter(it -> !it.equals(startAlias)).collect(Collectors.toSet()))
                .orElse(context.getAliasSet());

        code.add(createAliasDeclarations(aliasesExcludingStartAlias))
                .declare(ORDER_FIELDS_NAME, getPrimaryKeyFieldsWithTableAliasBlock(alias))
                .add("return $T.select(\n", DSL.className)
                .indent()
                .add("$T.inline($S).as($S),\n", DSL.className, implName, TYPE_FIELD)
                .add("$T.rowNumber().over($T.orderBy($L", DSL.className, DSL.className, ORDER_FIELDS_NAME)
                .add(")).as($S),\n", INNER_ROW_NUM)
                .add(getPrimaryKeyFieldsArray(implName, alias, context.getTargetTable().getName()))
                .add(".as($S))", PK_FIELDS)
                .add("\n.from($N)\n", alias)
                .add(whereBlock)
                .add(createSelectConditions(context.getConditionList(), !whereBlock.isEmpty()));

        if (isConnection) {
            code.add(".$L", whereBlock.isEmpty() ? "where" : "and")
                    .add("($N == null ? $T.noCondition() : $T.inline($S).greaterOrEqual($N.typeName()))",
                            TOKEN, DSL.className, DSL.className, implementation.getName(), TOKEN)
                    .add("\n.and($N != null && $N.matches($S) ? $T.row($L).gt($T.row($N.fields())) : $T.noCondition())",
                            TOKEN, TOKEN, implementation.getName(), DSL.className, getPrimaryKeyFieldsWithTableAliasBlock(alias), DSL.className, TOKEN, DSL.className);
        }
        return code.add(".orderBy($L)", ORDER_FIELDS_NAME)
                .addIf(isConnection, "\n.limit($N + 1)", PAGE_SIZE_NAME)
                .add(";")
                .unindent()
                .build();
    }

    private static CodeBlock getPrimaryKeyFieldsArray(String name, String alias, String tableName) {
        return CodeBlock.builder()
                .add("$T.jsonbArray($T.inline($S), ", DSL.className, DSL.className, name)
                .add(getPrimaryKeyFields(tableName, alias))
                .add(")").build();
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

    private static ParameterizedTypeName getReturnTypeForKeysMethod(boolean isConnection) {
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
                .filter(it -> !it.getName().equals(FEDERATION_ENTITIES_FIELD.getName()))
                .filter(it -> !it.getTypeName().equals(NODE_TYPE.getName()))
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .flatMap(it -> generateWithSubselectMethods(it).stream())
                .filter(it -> !it.code().isEmpty())
                .toList();
    }
}
