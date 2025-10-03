package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.TransformScope;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.objects.ConnectionObjectDefinition;
import no.sikt.graphitron.definitions.objects.EnumDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.naming.GraphQLReservedName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;
import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Key;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapArrayList;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Class containing various helper methods for constructing code with javapoet.
 */
public class FormatCodeBlocks {
    private final static CodeBlock
            COLLECT_TO_LIST = CodeBlock.of(".toList()"),
            NEW_TRANSFORM = CodeBlock.of("new $T($N)", RECORD_TRANSFORMER.className, VARIABLE_ENV),
            DECLARE_TRANSFORM = CodeBlock.declare(TRANSFORMER_NAME, NEW_TRANSFORM),
            NEW_DATA_FETCHER = CodeBlock.of("new $T($N)", DATA_FETCHER_HELPER.className, VARIABLE_ENV),
            NEW_SERVICE_DATA_FETCHER_TRANSFORM = CodeBlock.of("new $T<>($N)", DATA_SERVICE_FETCHER.className, TRANSFORMER_NAME),
            ATTACH = CodeBlock.of(".attach($N.configuration())", CONTEXT_NAME),
            ATTACH_RESOLVER = CodeBlock.of(".attach($L.configuration())", asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME)),
            TRUE_CONDITION = CodeBlock.of("$T.trueCondition()", DSL.className),
            FALSE_CONDITION = CodeBlock.of("$T.falseCondition()", DSL.className),
            NO_CONDITION = CodeBlock.of("$T.noCondition()", DSL.className),
            FIND_FIRST = CodeBlock.of(".stream().findFirst()"),
            EMPTY_LIST = CodeBlock.of("$T.of()", LIST.className),
            EMPTY_SET = CodeBlock.of("$T.of()", SET.className),
            EMPTY_MAP = CodeBlock.of("$T.of()", MAP.className);
    private final static String CONNECTION_NAME = "connection", PAGE_NAME = "page", EDGES_NAME = "edges", GRAPH_PAGE_NAME = "graphPage";

    /**
     * @param name Name of a field that should be declared as a record. This will be the name of the variable.
     * @param input Input type that should be declared as a record.
     * @param isIterable Is this record wrapped in a list?
     * @param isResolver Is this declaration to be used in a resolver?
     * @return CodeBlock that declares a new record variable and that attaches context configuration if needed.
     */
    public static CodeBlock declareRecord(String name, RecordObjectSpecification<?> input, boolean isIterable, boolean isResolver) {
        if (!input.hasRecordReference()) {
            return CodeBlock.empty();
        }

        return CodeBlock
                .builder()
                .declareNewIf(isIterable, asListedName(name), wrapArrayList(input.getRecordClassName()))
                .declareNewIf(!isIterable, name, input.getRecordClassName())
                .addStatementIf(!input.hasJavaRecordReference() && !isIterable, "$N$L", name, isResolver ? ATTACH_RESOLVER : ATTACH)
                .build();
    }

    public static CodeBlock recordTransformPart(String transformerName, String varName, String typeName, boolean isJava, boolean isInput) {
        return CodeBlock.of("$N.$L($N, ", transformerName, recordTransformMethod(typeName, isJava, isInput), uncapitalize(varName));
    }

    /**
     * @return CodeBlock that contains an if statement with a null check on the provided name.
     */
    @NotNull
    public static CodeBlock ifNotNull(String name) {
        return CodeBlock.of("if ($N != null)", name);
    }

    /**
     * @param addTarget Name of updatable collection to add something to.
     * @param addition The name of the content that should be added.
     * @return CodeBlock that adds something to an updatable collection.
     */
    @NotNull
    public static CodeBlock addToList(String addTarget, String addition) {
        return CodeBlock.statementOf("$N.add($N)", addTarget, addition);
    }

    /**
     * @param addTarget Name of updatable collection to add something to, as well as what is added. For the collection a "List" suffix is assumed.
     * @return CodeBlock that adds something to an updatable collection.
     */
    @NotNull
    public static CodeBlock addToList(String addTarget) {
        return addToList(asListedName(addTarget), uncapitalize(addTarget));
    }

    /**
     * @param addTarget Name of updatable collection to add something to.
     * @param codeAddition The CodeBlock that provides something that should be added.
     * @return CodeBlock that adds something to an updatable collection.
     */
    @NotNull
    public static CodeBlock addToList(String addTarget, CodeBlock codeAddition) {
        return CodeBlock.statementOf("$N.add($L)", addTarget, codeAddition);
    }

    /**
     * @return CodeBlock that creates an empty List.
     */
    @NotNull
    public static CodeBlock listOf() {
        return EMPTY_LIST;
    }

    /**
     * @return CodeBlock that wraps the supplied CodeBlock in a List.
     */
    @NotNull
    public static CodeBlock listOf(CodeBlock code) {
        return CodeBlock.ofIf(!code.isEmpty(), "$T.of($L)", LIST.className, code);
    }

    /**
     * @return CodeBlock that wraps the supplied variable name in a List.
     */
    @NotNull
    public static CodeBlock listOf(String variable) {
        return CodeBlock.of("$T.of($N)", LIST.className, variable);
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a Java list provided the condition is true.
     */
    @NotNull
    public static CodeBlock listOfIf(CodeBlock code, boolean condition) {
        if (!condition) {
            return code;
        }
        return listOf(indentIfMultiline(code));
    }

    /**
     * @return CodeBlock that creates an empty Set.
     */
    @NotNull
    public static CodeBlock setOf() {
        return EMPTY_SET;
    }

    /**
     * @return CodeBlock that wraps the supplied CodeBlock in a Set.
     */
    @NotNull
    public static CodeBlock setOf(CodeBlock code) {
        return CodeBlock.ofIf(!code.isEmpty(), "$T.of($L)", SET.className, code);
    }

    /**
     * @return CodeBlock that wraps the supplied variable name in a Set.
     */
    @NotNull
    public static CodeBlock setOf(String variable) {
        return CodeBlock.of("$T.of($N)", SET.className, variable);
    }

    /**
     * @return CodeBlock that creates an empty Map.
     */
    @NotNull
    public static CodeBlock mapOf() {
        return EMPTY_MAP;
    }

    /**
     * @return CodeBlock that wraps this method name in a method call format.
     */
    public static CodeBlock asMethodCall(String method) {
        return CodeBlock.of(".$L()", method);
    }

    /**
     * @return CodeBlock that wraps this method name in a method call format after the specified source.
     */
    public static CodeBlock asMethodCall(String source, String method) {
        return CodeBlock.of("$N$L", source, asMethodCall(method));
    }

    /**
     * @return CodeBlock that wraps this method name in a static method call format after the specified source.
     */
    public static CodeBlock asMethodCall(TypeName source, String method) {
        return CodeBlock.of("$T$L", source, asMethodCall(method));
    }

    /**
     * @return CodeBlock that wraps this variable in a Java cast.
     */
    public static CodeBlock asCast(TypeName type, String variable) {
        return CodeBlock.of("(($T) $N)", type, variable);
    }

    /**
     * @return CodeBlock that wraps this code in a Java cast.
     */
    public static CodeBlock asCast(TypeName type, CodeBlock code) {
        return CodeBlock.of("(($T) $L)", type, code);
    }

    /**
     * @return CodeBlock that adds a collect to List call to be used on a Stream.
     */
    @NotNull
    public static CodeBlock collectToList() {
        return COLLECT_TO_LIST;
    }

    /**
     * @return CodeBlock that adds a findFirst call to be used on a collection.
     */
    @NotNull
    public static CodeBlock findFirst() {
        return FIND_FIRST;
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock name in a simple null check.
     */
    @NotNull
    public static CodeBlock nullIfNullElse(CodeBlock code) {
        return CodeBlock.of("$L == null ? null : ", code);
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock name in a simple null check.
     */
    @NotNull
    public static CodeBlock nullIfNullElseThis(CodeBlock code) {
        return CodeBlock.join(nullIfNullElse(code), code);
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock name in a mapping null check.
     */
    @NotNull
    public static CodeBlock listedNullCheck(String variable, CodeBlock code) {
        return CodeBlock.of("$T.stream($N).allMatch($T::isNull) ? null : $L", ARRAYS.className, variable, OBJECTS.className, code);
    }

    /**
     * @return CodeBlock that adds something to a String if it is not empty.
     */
    @NotNull
    public static CodeBlock addStringIfNotEmpty(String target, String addition) {
        return CodeBlock.of("$N.isEmpty() ? $N : $N + $S", target, target, target, addition);
    }

    /**
     * @return CodeBlock that checks whether a path is in use.
     */
    @NotNull
    public static CodeBlock selectionSetLookup(String path, boolean atResolver, boolean useArguments) {
        if (!atResolver) {
            return CodeBlock.of("$N.contains($N + $S)", useArguments ? VARIABLE_ARGS : VARIABLE_SELECT, PATH_HERE_NAME, path);
        }
        return CodeBlock.of("$L.contains($S)", asMethodCall(TRANSFORMER_NAME, useArguments ? METHOD_ARGS_NAME : METHOD_SELECT_NAME), path);
    }

    /**
     * @return CodeBlock that sets a value through a mapping.
     */
    @NotNull
    public static CodeBlock setValue(String container, MethodMapping mapping, CodeBlock value, boolean isResolverKey) {
        return CodeBlock.of("$N$L", uncapitalize(container), isResolverKey ? mapping.asSetKeyCall(value) : mapping.asSetCall(value));
    }

    /**
     * @return CodeBlock that sets a value through a mapping.
     */
    @NotNull
    public static CodeBlock setValue(String container, MethodMapping mapping, CodeBlock value) {
        return CodeBlock.of("$N$L", uncapitalize(container), mapping.asSetCall(value));
    }
    /**
     * @return CodeBlock that gets a value through a mapping.
     */
    @NotNull
    public static CodeBlock getValue(String container, MethodMapping mapping) {
        return CodeBlock.of("$N$L", uncapitalize(container), mapping.asGetCall());
    }

    /**
     * @return CodeBlock that creates a data fetcher object.
     */
    @NotNull
    public static CodeBlock newDataFetcher() {
        return NEW_DATA_FETCHER;
    }

    /**
     * @return CodeBlock that creates a service data fetcher through a transform object.
     */
    @NotNull
    public static CodeBlock newServiceDataFetcherWithTransform() {
        return NEW_SERVICE_DATA_FETCHER_TRANSFORM;
    }

    /**
     * @return CodeBlock that declares a resolver transformer.
     */
    @NotNull
    public static CodeBlock declareTransform() {
        return DECLARE_TRANSFORM;
    }

    /**
     * @return CodeBlock does a null check on the variable and runs continue if it is.
     */
    @NotNull
    public static CodeBlock continueCheck(String value) {
        return CodeBlock.statementOf("if ($N == null) continue", value);
    }

    /**
     * @return CodeBlock consisting of a function for a count DB call.
     */
    @NotNull
    public static CodeBlock countFunction(String queryLocation, String queryMethodName, String inputList, boolean isService) {
        var params = new ArrayList<String>();

        var includeContext = !isService;
        if (includeContext) {
            params.add(CONTEXT_NAME);
        }

        if (GeneratorConfig.shouldMakeNodeStrategy()) {
            params.add(NODE_ID_STRATEGY_NAME);
        }

        if (!inputList.isEmpty()) {
            params.add(inputList);
        }

        return CodeBlock.of(
                isService ? "($L$L) -> $L.count$L($L)" : "($L$L) -> $T.count$L($L)",
                CodeBlock.ofIf(includeContext, "$L, ", CONTEXT_NAME),
                RESOLVER_KEYS_NAME,
                isService ? uncapitalize(queryLocation) : getQueryClassName(queryLocation),
                capitalize(queryMethodName),
                String.join(", ", params)
        );
    }

    public static ClassName getQueryClassName(String queryLocation) {
        return getGeneratedClassName(DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME, queryLocation);
    }

    /**
     * @return CodeBlock consisting of a function for a generic DB call.
     */
    @NotNull
    public static CodeBlock queryFunction(String queryLocation, String queryMethodName, String inputList, boolean hasKeyValues, boolean usesKeyValues, boolean isService) {
        var inputs = new ArrayList<String>();
        var params = new ArrayList<String>();
        if (!isService) {
            inputs.add(CONTEXT_NAME);
            params.add(CONTEXT_NAME);
            if (GeneratorConfig.shouldMakeNodeStrategy()) {
                params.add(NODE_ID_STRATEGY_NAME);
            }
        }
        if (hasKeyValues) {
            inputs.add(RESOLVER_KEYS_NAME);
        }
        if (usesKeyValues) {
            params.add(RESOLVER_KEYS_NAME);
        }
        if (!inputList.isEmpty()) {
            params.add(inputList);
        }
        if (!isService) {
            inputs.add(SELECTION_SET_NAME);
            params.add(SELECTION_SET_NAME);
        }
        var source = isService ? CodeBlock.of("$N", uncapitalize(queryLocation)) : CodeBlock.of("$T", getQueryClassName(queryLocation));
        return CodeBlock.of("($L) -> $L",
                String.join(", ", inputs),
                invokeExternalMethod(source, queryMethodName, String.join(", ", params)));
    }

    public static CodeBlock invokeExternalMethod(CodeBlock source, String methodName, String parameters) {
        return CodeBlock.of("$L.$L($L)",source, methodName, String.join(", ", parameters));
    }

    /**
     * @return CodeBlock for a function that maps relay connection types.
     */
    public static CodeBlock connectionFunction(ConnectionObjectDefinition connectionType, ObjectDefinition pageInfoType) {
        return CodeBlock
                .builder()
                .beginControlFlow("($L) -> ", CONNECTION_NAME)
                .declare(
                        EDGES_NAME,
                        "$N.getEdges().stream().map(it -> new $T($L.getValue(), it.getNode()))$L",
                        CONNECTION_NAME,
                        connectionType.getEdgeObject().getGraphClassName(),
                        nullIfNullElseThis(CodeBlock.of("it.getCursor()")),
                        collectToList()
                )
                .declare(PAGE_NAME, "$N.getPageInfo()", CONNECTION_NAME)
                .declare(
                        GRAPH_PAGE_NAME,
                        "new $T($N.isHasPreviousPage(), $N.isHasNextPage(), $L.getValue(), $L.getValue())",
                        pageInfoType.getGraphClassName(),
                        PAGE_NAME,
                        PAGE_NAME,
                        nullIfNullElseThis(CodeBlock.of("$N.getStartCursor()", PAGE_NAME)),
                        nullIfNullElseThis(CodeBlock.of("$N.getEndCursor()", PAGE_NAME))
                )
                .add(
                        returnWrap(
                                CodeBlock.of(
                                        "new $T($N, $N, $N.getNodes(), $N.getTotalCount())",
                                        connectionType.getGraphClassName(),
                                        EDGES_NAME,
                                        GRAPH_PAGE_NAME,
                                        CONNECTION_NAME,
                                        CONNECTION_NAME
                                )
                        )
                )
                .endControlFlow()
                .build();
    }

    /**
     * @return CodeBlock consisting of a function for an ID fetch DB call.
     */
    @NotNull
    public static CodeBlock getNodeQueryCallBlock(GenerationField field, String variableName, CodeBlock path, boolean atResolver) {
        var typeName = field.getTypeName();
        var idCall = CodeBlock.of(".getId()");
        return CodeBlock.of(
                "$T.$L($L, $L, $L.withPrefix($L))$L",
                getQueryClassName(asQueryClass(typeName)),
                asNodeQueryName(typeName),
                asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME),
                field.isIterableWrapped() ? CodeBlock.of("$N.stream().map(it -> it$L).collect($T.toSet())", variableName, idCall, COLLECTORS.className) : setOf(CodeBlock.of("$N$L", variableName, idCall)),
                atResolver ? asMethodCall(TRANSFORMER_NAME, METHOD_SELECT_NAME) : CodeBlock.of("$N", VARIABLE_SELECT),
                path,
                CodeBlock.ofIf(!field.isIterableWrapped(), ".values()$L.orElse(null)", findFirst())
        );
    }

    /**
     * @return CodeBlock consisting of a declaration of the page size variable through a method call.
     */
    @NotNull
    public static CodeBlock declarePageSize(int defaultFirst) {
        return CodeBlock.statementOf(
                "int $L = $T.getPageSize($N, $L, $L)",
                PAGE_SIZE_NAME,
                RESOLVER_HELPERS.className,
                GraphQLReservedName.PAGINATION_FIRST.getName(),
                GeneratorConfig.getMaxAllowedPageSize(),
                defaultFirst
        );
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in an if not null check.
     */
    @NotNull
    public static CodeBlock wrapNotNull(String valueToCheck, CodeBlock code) {
        return CodeBlock
                .builder()
                .beginControlFlow("$L", ifNotNull(valueToCheck))
                .add(code)
                .endControlFlow()
                .build();
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a for loop.
     */
    @NotNull
    public static CodeBlock wrapFor(String variable, CodeBlock code) {
        return CodeBlock
                .builder()
                .beginControlFlow("for (var $L : $N)", asIterable(variable), variable)
                .add(code)
                .endControlFlow()
                .build();
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in an indexed for loop.
     */
    @NotNull
    public static CodeBlock wrapForIndexed(String variable, CodeBlock code) {
        return CodeBlock
                .builder()
                .beginControlFlow("for (int $1L = 0; $1N < $2N.size(); $1N++)", asIndexName(asIterable(variable)), variable)
                .add(code)
                .endControlFlow()
                .build();
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a jOOQ row.
     */
    @NotNull
    public static CodeBlock wrapRow(CodeBlock code) {
        return CodeBlock.ofIf(!code.isEmpty(), "$T.row($L)", DSL.className, indentIfMultiline(code));
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a jOOQ row.
     */
    @NotNull
    public static CodeBlock wrapObjectRow(CodeBlock code) {
        return CodeBlock.ofIf(!code.isEmpty(), "$T.objectRow($L)", QUERY_HELPER.className, indentIfMultiline(code));
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a jOOQ coalesce.
     */
    @NotNull
    public static CodeBlock wrapCoalesce(CodeBlock code) {
        return CodeBlock.ofIf(!code.isEmpty(), "$T.coalesce($L)", DSL.className, indentIfMultiline(code));
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a jOOQ inline.
     */
    @NotNull
    public static CodeBlock inline(CodeBlock code) {
        return CodeBlock.ofIf(!code.isEmpty(), "$T.inline($L)", DSL.className, code);
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a jOOQ val.
     */
    @NotNull
    public static CodeBlock val(CodeBlock code) {
        return CodeBlock.ofIf(!code.isEmpty(), "$T.val($L)", DSL.className, code);
    }


    /**
     * @return Add appropriate indentation if this code has multiple lines.
     */
    @NotNull
    public static CodeBlock indentIfMultiline(CodeBlock code) {
        if (!code.toString().contains("\n")) {
            return code;
        }

        return CodeBlock
                .builder()
                .add("\n")
                .indent()
                .indent()
                .add(code)
                .add("\n")
                .unindent()
                .unindent()
                .build();
    }

    /**
     * @return CodeBlock that sends this variable through an enum mapping.
     */
    public static CodeBlock makeEnumMapBlock(CodeBlock inputVariable, CodeBlock valueLists) {
        return CodeBlock.of("$T.makeEnumMap($L, $L)", QUERY_HELPER.className, inputVariable, valueLists);
    }

    /**
     * @return CodeBlock that sends this variable through an enum mapping.
     */
    public static CodeBlock makeEnumMapBlock(String inputVariable, CodeBlock valueLists) {
        return makeEnumMapBlock(CodeBlock.of(inputVariable), valueLists);
    }

    /**
     * @return Code block containing the enum conversion method call with anonymous function declarations.
     */
    public static CodeBlock toJOOQEnumConverter(String enumType, ProcessedSchema schema) {
        if (!schema.isEnum(enumType)) {
            return CodeBlock.empty();
        }

        var enumEntry = schema.getEnum(enumType);
        var tempVariableName = "s";
        var code = CodeBlock.of(
                "$T.class,\n$L -> $L,\n$L -> $L",
                enumEntry.getGraphClassName(),
                tempVariableName,
                makeEnumMapBlock(tempVariableName, renderEnumMapElements(enumEntry, true)),
                tempVariableName,
                makeEnumMapBlock(tempVariableName, renderEnumMapElements(enumEntry, false))
        );
        return CodeBlock.of(".convert($L)", indentIfMultiline(code));
    }

    /**
     * @return Code block containing the enum conversion method call.
     */
    public static CodeBlock toGraphEnumConverter(String enumType, CodeBlock field, boolean toRecord, ProcessedSchema schema) {
        if (!schema.isEnum(enumType)) {
            return CodeBlock.empty();
        }
        return makeEnumMapBlock(field, renderEnumMapElements(schema.getEnum(enumType), !toRecord));
    }

    private static CodeBlock renderEnumMapElements(EnumDefinition enumEntry, boolean flipDirection) {
        var hasEnumReference = enumEntry.hasJavaEnumMapping();
        var enumReference = enumEntry.getEnumReference();
        var entryClassName = enumEntry.getGraphClassName();
        var referenceClassName = enumReference != null
                ? ClassName.get(GeneratorConfig.getExternalReferences().getClassFrom(enumReference))
                : null;

        var fromBlocks = new ArrayList<CodeBlock>();
        var toBlocks = new ArrayList<CodeBlock>();
        for (var enumValue : enumEntry.getFields()) {
            var name = enumValue.getUnprocessedFieldOverrideInput();
            var key = CodeBlock.of("$T.$L", entryClassName, enumValue.getName());
            var value = hasEnumReference ? CodeBlock.of("$T.$L", referenceClassName, name) : CodeBlock.of("$S", name);
            fromBlocks.add(flipDirection ? value : key);
            toBlocks.add(flipDirection ? key : value);
        }

        return CodeBlock.of(
                "$L, $L",
                listOf(CodeBlock.join(fromBlocks, ", ")),
                listOf(CodeBlock.join(toBlocks, ", "))
        );
    }

    /**
     * @return Code block of a true jOOQ condition.
     */
    public static CodeBlock trueCondition() {
        return TRUE_CONDITION;
    }

    /**
     * @return Code block of a false jOOQ condition.
     */
    public static CodeBlock falseCondition() {
        return FALSE_CONDITION;
    }

    /**
     * @return Code block of a jOOQ "no condition".
     */
    public static CodeBlock noCondition() {
        return NO_CONDITION;
    }

    /**
     * @param recordName Name of the record to transform.
     * @param scope      The scope of transforms that should be applied. Currently only {@link TransformScope#ALL_MUTATIONS} is supported.
     * @return CodeBlock where all defined global transforms are applied to the record.
     */
    public static CodeBlock applyGlobalTransforms(String recordName, TypeName recordTypeName, TransformScope scope) {
        return GeneratorConfig
                .getGlobalTransforms(scope)
                .stream()
                .map(it -> getMethodFrom(it.getFullyQualifiedClassName(), it.getMethod()))
                .map(transform -> applyTransform(recordName, recordTypeName, transform))
                .collect(CodeBlock.joining());
    }

    private static Method getMethodFrom(String fullyQualifiedClassName, String methodName) {
        try {
            var classReference = Class.forName(fullyQualifiedClassName);
            return Arrays.stream(classReference.getMethods())
                    .filter(it -> it.getName().equalsIgnoreCase(methodName))
                    .findFirst()
                    .orElseThrow(() ->  new IllegalArgumentException("Could not find method with name " + methodName + " in external class " + fullyQualifiedClassName));

        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Could not find external class " + fullyQualifiedClassName);
        }
    }

    /**
     * @param recordName Name of the record to transform.
     * @param transform  The method that should transform the record.
     * @return CodeBlock where the transform is applied to the record.
     */
    public static CodeBlock applyTransform(String recordName, TypeName recordTypeName, Method transform) {
        return CodeBlock.statementOf(
                "$N = ($T) $T.$L($N, $N)",
                asListedName(recordName),
                wrapArrayList(recordTypeName),
                ClassName.get(transform.getDeclaringClass()),
                transform.getName(),
                CONTEXT_NAME,
                asListedName(recordName)
        );
    }

    public static CodeBlock fetchMapping(boolean iterable) {
        return iterable
                ? CodeBlock.of("$1L -> $1N.value1().intoMap(), $1L -> $1N.value2().intoMap()", VARIABLE_INTERNAL_ITERATION)
                : CodeBlock.of(".fetchOneMap()");
    }

    /**
     * @return CodeBlock that returns the provided name.
     */
    @NotNull
    public static CodeBlock returnWrap(String variable) {
        return CodeBlock.statementOf("return $N", variable);
    }

    /**
     * @return CodeBlock that returns the provided code.
     */
    @NotNull
    public static CodeBlock returnWrap(CodeBlock code) {
        return CodeBlock.statementOf("return $L", code);
    }

    /**
     * @return CodeBlock that wraps and returns the provided variable in a CompletableFuture.
     */
    @NotNull
    public static CodeBlock returnCompletedFuture(String variable) {
        return returnWrap(CodeBlock.of("$T.completedFuture($N)", COMPLETABLE_FUTURE.className, variable));
    }

    /**
     * @return CodeBlock that wraps and returns the provided CodeBlock in a CompletableFuture.
     */
    @NotNull
    public static CodeBlock returnCompletedFuture(CodeBlock code) {
        return returnWrap(CodeBlock.of("$T.completedFuture($L)", COMPLETABLE_FUTURE.className, code));
    }

    public static CodeBlock inResolverKeysBlock(String resolverKeyParamName, FetchContext context) {
        return CodeBlock.of("$L.in($N)", getSelectKeyColumnRow(context), resolverKeyParamName);
    }

    /**
     * Returns the select code for the columns of a key.
     *
     * @param key               The key
     * @param aliasVariableName The variable name for the table alias
     * @return Select code for the columns in the key
     */
    public static CodeBlock getSelectKeyColumnRow(Key<?> key, String tableName, String aliasVariableName) {
        return wrapRow(
                getSelectKeyColumn(key, tableName, aliasVariableName)
        );
    }

    /**
     * Returns codeblock for selecting key columns for the resolver key
     * @param context The fetching context
     * @return Select code for the columns in the resolver key
     */
    public static CodeBlock getSelectKeyColumnRow(FetchContext context) {
        var table = hasIterableWrappedResolverWithPagination(context)
                    ? context.getTargetTableName()
                    : context.getSourceTableName();
        var alias = hasIterableWrappedResolverWithPagination(context)
                    ? context.getTargetAlias()
                    : context.getSourceAlias();

        return getSelectKeyColumnRow(context.getResolverKey().key(), table, alias);
    }

    public static CodeBlock getSelectKeyColumn(Key<?> key, String tableName, String aliasVariableName) {
        return getJavaFieldNamesForKey(tableName, key)
                .stream()
                .map(it -> CodeBlock.of("$N.$L", aliasVariableName, it))
                .collect(CodeBlock.joining(", "));
    }

    public static CodeBlock getSelectKeyColumn(FetchContext context) {
        var table = hasIterableWrappedResolverWithPagination(context)
                    ? context.getTargetTableName()
                    : context.getSourceTableName();
        var alias = hasIterableWrappedResolverWithPagination(context)
                    ? context.getTargetAlias()
                    : context.getSourceAlias();

        return getSelectKeyColumn(context.getResolverKey().key(), table, alias);
    }

    public static CodeBlock createNodeIdBlock(RecordObjectSpecification<?> obj, String targetAlias) {
        return CodeBlock.of("$N.createId($S, $L)",
                NODE_ID_STRATEGY_NAME,
                obj.getTypeId(),
                nodeIdColumnsWithAliasBlock(targetAlias, obj)
        );
    }

    public static CodeBlock createNodeIdBlockForRecord(RecordObjectSpecification<?> obj, String recordVariableName) {
        return CodeBlock.of("$N.createId($N, $S, $L)",
                NODE_ID_STRATEGY_NAME,
                recordVariableName,
                obj.getTypeId(),
                nodeIdColumnsBlock(obj)
        );
    }

    public static CodeBlock hasIdBlock(CodeBlock id, RecordObjectSpecification<?> obj, String targetAlias) {
        return hasIdOrIdsBlock(id, obj, targetAlias, CodeBlock.empty(), false);
    }

    public static CodeBlock reassignFromServiceBlock(String variableName, String methodName, String targetAlias, String args) {
        return CodeBlock.of(
                "$N = $L",
                targetAlias,
                invokeExternalMethod(
                        CodeBlock.of("$N", uncapitalize(variableName)),
                        methodName,
                        args)
        );
    }

    public static CodeBlock hasIdsBlock(RecordObjectSpecification<?> obj, String targetAlias) {
        return hasIdOrIdsBlock(CodeBlock.of(IDS_NAME), obj, targetAlias, CodeBlock.empty(), true);
    }

    public static CodeBlock hasIdOrIdsBlock(CodeBlock idOrRecordParamName, RecordObjectSpecification<?> obj, String targetAlias, CodeBlock mappedFkFields, boolean isMultiple) {
        return CodeBlock.of("$N.$L($S, $L, $L)",
                NODE_ID_STRATEGY_NAME,
                isMultiple ? "hasIds" : "hasId",
                obj.getTypeId(),
                idOrRecordParamName,
                mappedFkFields.isEmpty() ? nodeIdColumnsWithAliasBlock(targetAlias, obj) : mappedFkFields
        );
    }

    public static CodeBlock nodeIdColumnsBlock(RecordObjectSpecification<?> obj) {
        if (obj.hasCustomKeyColumns()) {
            return obj.getKeyColumns().stream().map(it -> CodeBlock.of("$L.$L", staticTableInstanceBlock(obj.getTable().getName()), it))
                    .collect(CodeBlock.joining(", "));
        }
        return getPrimaryKeyFieldsBlock(staticTableInstanceBlock(obj.getTable().getName()));
    }

    public static CodeBlock referenceNodeIdColumnsBlock(RecordObjectSpecification<?> container, RecordObjectSpecification<?> target, ForeignKey<?,?> fk) {
        return referenceNodeIdColumnsBlock(container, target, fk, staticTableInstanceBlock(container.getTable().getName()));
    }

    public static CodeBlock referenceNodeIdColumnsBlock(RecordObjectSpecification<?> container, RecordObjectSpecification<?> target, ForeignKey<?,?> fk, CodeBlock tableReference) {
        var mapping = new HashMap<String, Field<?>>();
        var sourceColumns = fk.getFields();
        var targetColumns = fk.getInverseKey().getFields();

        for (int i = 0; i < sourceColumns.size(); i++) {
            mapping.put(targetColumns.get(i).getName(), sourceColumns.get(i));
        }

        var sourceTable = target.getTable().getName();
        var targetTable = container.getTable().getName();

        var targetNodeIdFields = target.hasCustomKeyColumns() ? target.getKeyColumns()
                : getPrimaryKeyForTable(sourceTable)
                .orElseThrow(() -> new IllegalArgumentException("Cannot find primary key for table " + sourceTable)) // This should be validated and never thrown
                .getFields()
                .stream()
                .map(Field::getName)
                .toList();

        return targetNodeIdFields.stream()
                .map(it -> mapping.keySet().stream()
                        .filter(fieldName -> fieldName.equalsIgnoreCase(it)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Node ID field " + it + " is not found in foreign key " + fk.getName() + "'s fields."))) // Should never be thrown
                .map(mapping::get)
                .map(it -> CodeBlock.of("$L.$L", tableReference, getJavaFieldName(targetTable, it.getName()).orElseThrow()))
                .collect(CodeBlock.joining(", "));
    }

    private static CodeBlock staticTableInstanceBlock(String tableName) {
        var tableClass = getTable(tableName)
                .orElseThrow(() -> new RuntimeException("Unknown table " + tableName))
                .getClass();
        return CodeBlock.of("$T.$N", tableClass, tableName);
    }

    public static CodeBlock nodeIdColumnsWithAliasBlock(String targetAlias, RecordObjectSpecification<?> obj) {
        if (obj.hasCustomKeyColumns()) {
            return obj.getKeyColumns().stream().map(it -> CodeBlock.of("$N.$L", targetAlias, it))
                    .collect(CodeBlock.joining(", "));
        }
        return getPrimaryKeyFieldsWithTableAliasBlock(targetAlias);
    }

    public static CodeBlock getPrimaryKeyFieldsWithTableAliasBlock(String targetAlias) {
        return CodeBlock.of("$N.fields($L)", targetAlias, getPrimaryKeyFieldsBlock(targetAlias));
    }

    private static @NotNull CodeBlock getPrimaryKeyFieldsBlock(String target) {
        return getPrimaryKeyFieldsBlock(CodeBlock.of(target));
    }

    private static @NotNull CodeBlock getPrimaryKeyFieldsBlock(CodeBlock target) {
        return CodeBlock.of("$L.getPrimaryKey().getFieldsArray()", target);
    }

    public static CodeBlock ofTernary(CodeBlock ifExpr, CodeBlock thenExpr, CodeBlock elseExpr) {
        return CodeBlock.of("$L ? $L : $L", ifExpr, thenExpr, elseExpr);
    }

    private static boolean hasIterableWrappedResolverWithPagination(FetchContext context) {
        return context.getReferenceObjectField().isResolver() &&
               context.getReferenceObjectField().isIterableWrapped() &&
               ((ObjectField) context.getReferenceObjectField()).hasForwardPagination();
    }
}
