package no.sikt.graphitron.generators.db.fetch;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
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

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.db.fetch.FetchSingleTableInterfaceDBMethodGenerator.TOKEN;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.getPrimaryKeyForTable;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

public class FetchMultiTableDBMethodGenerator extends FetchDBMethodGenerator {

    public static final String UNION_KEYS_QUERY = "unionKeysQuery";
    public static final String RESULT = "_result";
    public static final String TYPE_FIELD = "$type";
    public static final String DATA_FIELD = "$data";
    public static final String PK_FIELDS = "$pkFields";
    public static final String INNER_ROW_NUM = "$innerRowNum";
    public static final int MAPPED_START_INDEX_IN_SELECT = 1;

    public FetchMultiTableDBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema
    ) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var inputParser = new InputParser(target, processedSchema);

        var unionOrInterfaceDefinition = processedSchema.isUnion(target)? processedSchema.getUnion(target): processedSchema.getInterface(target);

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

        for (var implementation : implementations) {
            if (!implementation.hasTable()) {
                throw new IllegalArgumentException(String.format("Type '%s' is returned in an interface query, but not have table set. This is not supported.", implementation.getName()));
            }
            String typeName = implementation.getName();
            sortFieldQueryMethodCalls.add(getSortFieldsMethodName(target, implementation));
            String mappedVariableName = "mapped" + typeName;
            mappedQueryVariables.put(typeName, mappedVariableName);
            mappedDeclarationBlock.add(declare(mappedVariableName,
                    CodeBlock.of("$N($L)",
                            getMappedMethodName(target, implementation),
                            GeneratorConfig.shouldMakeNodeStrategy() ? NODE_ID_STRATEGY_NAME : empty()))
            );

            joins.add("\n.leftJoin($N)\n.on($N.field($S, $T.class).eq($N.field($S, $T.class)))",
                    mappedVariableName,
                    UNION_KEYS_QUERY, PK_FIELDS, JSONB.className,
                    mappedVariableName, PK_FIELDS, JSONB.className);
        }

        var unionQuery = getUnionQuery(sortFieldQueryMethodCalls, inputs, target.hasForwardPagination());
        var mapping = createMapping(target, implementations);

        var returnsMultiple = target.isIterableWrapped() || target.hasForwardPagination();

        return CodeBlock.builder()
                .add(target.hasForwardPagination() ? declare(TOKEN, getTokenVariableDeclaration(implementations)) : empty())
                .add(declare(UNION_KEYS_QUERY, unionQuery))
                .add("\n")
                .add(mappedDeclarationBlock.build())
                .add("\n")
                .add("$L ", returnsMultiple ? "return" : String.format("var %s =", RESULT))
                .indent()
                .add("$N.select($L)", CONTEXT_NAME, createSelectBlock(mappedQueryVariables, !target.hasForwardPagination()))
                .add("\n.from($L)", UNION_KEYS_QUERY)
                .add(joins.build())
                .add("\n.orderBy($N.field($S), $N.field($S))", UNION_KEYS_QUERY, TYPE_FIELD, UNION_KEYS_QUERY, INNER_ROW_NUM)
                .add(target.hasForwardPagination() ? CodeBlock.of(".limit($N + 1)\n", PAGE_SIZE_NAME) : empty())
                .add(returnsMultiple ? "\n.fetch()\n" : "\n.fetchOne();")
                .add(returnsMultiple ? mapping : empty())
                .unindent()
                .add(returnsMultiple ? empty() : mapping)
                .build();
    }

    private static @NotNull CodeBlock getTokenVariableDeclaration(Set<ObjectDefinition> implementations) {
        var code = CodeBlock.builder()
                .add("$T.getOrderByValuesForMultitableInterface($N, \n$T.of(", QUERY_HELPER.className, CONTEXT_NAME, MAP.className)
                .indent();

        var mapEntries = implementations.stream()
                .map(implementation ->
                        CodeBlock.of("\n$S, $L", implementation.getName(), getPrimaryKeyFieldsBlock(implementation.getTable().getName())))
                .collect(Collectors.toList());

        return code
                .add(CodeBlock.join(mapEntries, ","))
                .unindent()
                .add("\n),\n$N)", PAGINATION_AFTER.getName())
                .build();
    }

    private static @NotNull CodeBlock getUnionQuery(List<String> subselectVariableNames, Set<String> inputNames, boolean isConnection) {

        var inputs = CodeBlock.builder()
                .add(isConnection ? CodeBlock.of("$N, $N", PAGE_SIZE_NAME, TOKEN) : empty())
                .add(isConnection && !inputNames.isEmpty() ? ", " : "")
                .add(CodeBlock.join(inputNames.stream().map(CodeBlock::of).collect(Collectors.toSet()), ", "))
                .build();

            return CodeBlock.of(subselectVariableNames.stream()
                    .reduce("",
                            (currString, element) -> {
                                if (currString.isEmpty()) {
                                    return String.format("%s(%s)", element, inputs);
                                } else {
                                    return String.format("%s(%s)\n.unionAll(%s)", element, inputs, currString);
                                }
                            })
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

    private CodeBlock createMapping(ObjectField target, Set<ObjectDefinition> implementations) {
        var code = CodeBlock.builder();
        var graphClassName = processedSchema.isInterface(target) ? processedSchema.getInterface(target).getGraphClassName() : processedSchema.getUnion(target).getGraphClassName();
        var mapping = createMappingContent(graphClassName, implementations, target.hasForwardPagination());
        if (target.isIterableWrapped() || target.hasForwardPagination()) {
            code.add(".map(\n$L\n);", mapping);
        } else {
            code.add("\nreturn $L == null ? null : $L.map(\n$L\n);", RESULT, RESULT, mapping);
        }
        return code.build();
    }

    private CodeBlock createMappingContent(ClassName interfaceClassName, Set<ObjectDefinition> implementations, boolean isConnection) {
        var code = CodeBlock.builder()
                .indent()
                .beginControlFlow("$N -> ", VARIABLE_INTERNAL_ITERATION)
                .add(isConnection ? CodeBlock.of("$T $N;\n", RECORD2.className, RESULT) : empty())
                .beginControlFlow("switch ($N.get(0, $T.class))", VARIABLE_INTERNAL_ITERATION, STRING.className);

        if (isConnection) {
            int i = MAPPED_START_INDEX_IN_SELECT;

            for (var implementation : implementations) {
                code.add("case $S:\n", implementation.getName())
                        .indent()
                        .add("$N = $N.get($L, $T.class); break;\n", RESULT, VARIABLE_INTERNAL_ITERATION, i, RECORD2.className)
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
            code.addStatement("return $T.of($N.get(0, $T.class), $N.get(1, $T.class))", PAIR.className, RESULT, STRING.className, RESULT, interfaceClassName);
        }

        return code.endControlFlow()
                .unindent()
                .build();
    }

    public List<MethodSpec> generateWithSubselectMethods(ObjectField target) {
        var methods = new ArrayList<MethodSpec>();
        methods.add(generate(target));
        var inputParser = new InputParser(target, processedSchema);
        var unionOrInterfaceDefinition = processedSchema.isUnion(target)? processedSchema.getUnion(target): processedSchema.getInterface(target);

        processedSchema
                .getTypesFromInterfaceOrUnion(unionOrInterfaceDefinition.getName())
                .forEach(implementation -> {
                    var virtualReference = new VirtualSourceField(implementation, target.getTypeName(), target.getNonReservedArguments(), target.getCondition());
                    var context = new FetchContext(processedSchema, virtualReference, implementation, false);

                    var sortFieldsMethod = MethodSpec
                            .methodBuilder(getSortFieldsMethodName(target, implementation))
                            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                            .returns(getReturnTypeForKeysMethod(target.hasForwardPagination()))
                            .addCode(getSortFieldsMethodCode(implementation, context, target));

                    if (target.hasForwardPagination()) {
                        sortFieldsMethod
                                .addParameter(Integer.class, PAGE_SIZE_NAME)
                                .addParameter(AfterTokenWithTypeName.class, TOKEN);
                    }

                    inputParser.getMethodInputs()
                            .forEach((key, value) -> sortFieldsMethod.addParameter(iterableWrapType(value), key));

                    var mappedMethod = MethodSpec
                            .methodBuilder(getMappedMethodName(target, implementation))
                            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                            .returns(target.hasForwardPagination() ?
                                    getReturnTypeForMappedConnectionMethod(implementation.getGraphClassName())
                                    : getReturnTypeForMappedMethod(implementation.getGraphClassName()))
                            .addCode(getMappedMethodCode(target, implementation, context));

                    if (GeneratorConfig.shouldMakeNodeStrategy()) {
                        mappedMethod.addParameter(NODE_ID_STRATEGY.className, NODE_ID_STRATEGY_NAME);
                    }

                    methods.add(sortFieldsMethod.build());
                    methods.add(mappedMethod.build());
                });
        return methods;
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
                .add(target.hasForwardPagination() ? CodeBlock.of("$T.row(\n", DSL.className) : empty())
                .add(target.hasForwardPagination() ? CodeBlock.of("$T.getOrderByTokenForMultitableInterface($L, $L, $S),\n",
                        QUERY_HELPER.className, context.getTargetAlias(), getPrimaryKeyFieldsBlock(context.getTargetAlias()), implementation.getName()) : empty())
                .add("$T.select($L)", DSL.className, indentIfMultiline(selectCode))
                .unindent()
                .add(target.hasForwardPagination() ? CodeBlock.of(")\n") : empty())
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
        var whereBlock = formatWhereContents(context, idParamName, isRoot, false);
        String implName = implementation.getName();

        code.add(createAliasDeclarations(context.getAliasSet()))
                .add(declare(ORDER_FIELDS_NAME, getPrimaryKeyFieldsBlock(alias)))
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
                        TOKEN, TOKEN, implementation.getName(), DSL.className, getPrimaryKeyFieldsBlock(alias), DSL.className, TOKEN, DSL.className);
        }
        return code.add(".orderBy($L)", ORDER_FIELDS_NAME)
                    .add(isConnection ? CodeBlock.of("\n.limit($N + 1);", PAGE_SIZE_NAME) : CodeBlock.of(";"))
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
                .filter(it -> processedSchema.isInterface(it) || (processedSchema.isUnion(it) && !it.getName().equals(FEDERATION_ENTITIES_FIELD.getName())))
                .filter(it -> !it.getTypeName().equals(NODE_TYPE.getName()))
                .filter(it-> !processedSchema.isInterface(it) || !processedSchema.getInterface(it).hasDiscriminator())
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .flatMap(it -> generateWithSubselectMethods(it).stream())
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}
