package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.TransformScope;
import no.sikt.graphitron.definitions.helpers.NodeConfiguration;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.objects.EnumDefinition;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;
import org.jooq.Key;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.asListedName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.recordTransformMethod;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapArrayList;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.getJavaFieldNamesForKey;
import static no.sikt.graphitron.mappings.TableReflection.getTableByJavaFieldName;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Class containing various helper methods for constructing code with javapoet.
 */
public class FormatCodeBlocks {
    private final static CodeBlock
            COLLECT_TO_LIST = CodeBlock.of(".toList()"),
            NEW_TRANSFORM = CodeBlock.of("new $T($N)", RECORD_TRANSFORMER.className, VAR_ENV),
            DECLARE_TRANSFORM = CodeBlock.declare(VAR_TRANSFORMER, NEW_TRANSFORM),
            NEW_DATA_FETCHER = CodeBlock.of("new $T($N)", DATA_FETCHER_HELPER.className, VAR_ENV),
            NEW_SERVICE_DATA_FETCHER_TRANSFORM = CodeBlock.of("new $T<>($N)", DATA_SERVICE_FETCHER.className, VAR_TRANSFORMER),
            ATTACH = CodeBlock.of(".attach($N.configuration())", VAR_CONTEXT),
            ATTACH_RESOLVER = CodeBlock.of(".attach($L.configuration())", asMethodCall(VAR_TRANSFORMER, METHOD_CONTEXT_NAME)),
            TRUE_CONDITION = CodeBlock.of("$T.trueCondition()", DSL.className),
            FALSE_CONDITION = CodeBlock.of("$T.falseCondition()", DSL.className),
            NO_CONDITION = CodeBlock.of("$T.noCondition()", DSL.className),
            FIND_FIRST = CodeBlock.of(".stream().findFirst()"),
            EMPTY_LIST = CodeBlock.of("$T.of()", LIST.className),
            EMPTY_SET = CodeBlock.of("$T.of()", SET.className),
            EMPTY_MAP = CodeBlock.of("$T.of()", MAP.className);

    /**
     * @param name Name of a field that should be declared as a record. This will be the name of the variable.
     * @param input Input type that should be declared as a record.
     * @param isIterable Is this record wrapped in a list?
     * @param createsDataFetchers Is this declaration to be used in a resolver?
     * @return CodeBlock that declares a new record variable and that attaches context configuration if needed.
     */
    public static CodeBlock declareRecord(String name, RecordObjectSpecification<?> input, boolean isIterable, boolean createsDataFetchers) {
        if (!input.hasRecordReference()) {
            return CodeBlock.empty();
        }

        return CodeBlock
                .builder()
                .declareNewIf(isIterable, asListedName(name), wrapArrayList(input.getRecordClassName()))
                .declareNewIf(!isIterable, name, input.getRecordClassName())
                .addStatementIf(!input.hasJavaRecordReference() && !isIterable, "$N$L", name, createsDataFetchers ? ATTACH_RESOLVER : ATTACH)
                .build();
    }

    public static CodeBlock recordTransformPart(String transformerName, String varName, String typeName, boolean isJava, boolean isInput) {
        return CodeBlock.of("$N.$L($N, ", transformerName, recordTransformMethod(typeName, isJava, isInput), uncapitalize(varName));
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
     * @return CodeBlock that wraps the supplied CodeBlock in a Map.
     */
    public static CodeBlock mapOf(CodeBlock code) {
        return CodeBlock.of("$T.of($L)", MAP.className, code);
    }

    /**
     * @return CodeBlock that wraps the supplied CodeBlock in a Map using ofEntries.
     *         Use this instead of {@link #mapOf(CodeBlock)} when the map may have more than 10 entries.
     */
    public static CodeBlock mapOfEntries(CodeBlock code) {
        return CodeBlock.of("$T.ofEntries($L)", MAP.className, code);
    }

    /**
     * @return CodeBlock that wraps the supplied key string and value CodeBlocks in a Map.Entry.
     */
    public static CodeBlock mapEntry(String key, CodeBlock value) {
        return CodeBlock.of("$T.entry($S, $L)", MAP.className, key, value);
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
            return CodeBlock.of("$N.contains($N + $S)", useArguments ? VAR_ARGS : VAR_SELECT, VAR_PATH_HERE, path);
        }
        return CodeBlock.of("$L.contains($S)", asMethodCall(VAR_TRANSFORMER, useArguments ? METHOD_ARGS_NAME : METHOD_SELECT_NAME), path);
    }

    /**
     * @return CodeBlock that checks whether a field is present in the ArgumentPresence tree.
     * The path may contain slashes for nested non-record wrapper types (e.g. "inner/postalCode"),
     * in which case intermediate segments are navigated via .child() calls.
     */
    @NotNull
    public static CodeBlock argumentPresenceLookup(String path, boolean atResolver) {
        var parts = path.split("/");
        var fieldName = parts[parts.length - 1];
        CodeBlock base = atResolver
                ? CodeBlock.of("$L", asMethodCall(VAR_TRANSFORMER, METHOD_ARG_PRESENCE_NAME))
                : CodeBlock.of("$N", VAR_ARGS);
        for (int i = 0; i < parts.length - 1; i++) {
            base = CodeBlock.of("$L.$L($S)", base, METHOD_CHILD, parts[i]);
        }
        return CodeBlock.of("$L.$L($S)", base, METHOD_HAS_FIELD, fieldName);
    }

    /**
     * @return CodeBlock that sets a value through a mapping.
     */
    @NotNull
    public static CodeBlock setValue(String container, MethodMapping mapping, CodeBlock value, boolean isDataFetcherKey) {
        return CodeBlock.of("$N$L", uncapitalize(container), isDataFetcherKey ? mapping.asSetKeyCall(value) : mapping.asSetCall(value));
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
    public static CodeBlock countFunction(String queryLocation, String queryMethodName, List<String> inputList, boolean isService) {
        return CodeBlock.of(
                isService ? "($L$L) -> $L.count$L($L)" : "($L$L) -> $T.count$L($L)",
                CodeBlock.ofIf(!isService, "$L, ", VAR_CONTEXT),
                VAR_RESOLVER_KEYS,
                isService ? uncapitalize(queryLocation) : getQueryClassName(queryLocation),
                capitalize(queryMethodName),
                String.join(", ", inputList)
        );
    }

    public static ClassName getQueryClassName(String queryLocation) {
        return getGeneratedClassName(DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME, queryLocation);
    }

    /**
     * @return CodeBlock consisting of a function for a generic DB call.
     */
    @NotNull
    public static CodeBlock queryFunction(String queryLocation, String queryMethodName, List<String> inputList, boolean hasKeyValues, boolean usesKeyValues, boolean isService) {
        var inputs = new ArrayList<String>();
        var params = new ArrayList<String>();
        if (!isService) {
            inputs.add(VAR_CONTEXT);
            params.add(VAR_CONTEXT);
            if (GeneratorConfig.shouldMakeNodeStrategy()) {
                params.add(VAR_NODE_STRATEGY);
            }
        }
        if (hasKeyValues) {
            inputs.add(VAR_RESOLVER_KEYS);
        }
        if (usesKeyValues) {
            params.add(VAR_RESOLVER_KEYS);
        }
        params.addAll(inputList);
        if (!isService) {
            inputs.add(VAR_SELECTION_SET);
            params.add(VAR_SELECTION_SET);
        }
        var source = isService ? CodeBlock.of("$N", uncapitalize(queryLocation)) : CodeBlock.of("$T", getQueryClassName(queryLocation));
        return CodeBlock.of("($L) -> $L",
                String.join(", ", inputs),
                invokeExternalMethod(source, queryMethodName, CodeBlock.of(String.join(", ", params))));
    }

    public static CodeBlock invokeExternalMethod(CodeBlock source, String methodName, CodeBlock parameters) {
        return CodeBlock.of("$L.$L($L)", source, methodName, parameters);
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in an if not null check.
     */
    @NotNull
    public static CodeBlock wrapNotNull(String valueToCheck, CodeBlock code) {
        return wrapNotNull(CodeBlock.of("$N", valueToCheck), code);
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in an if not null check.
     */
    @NotNull
    public static CodeBlock wrapNotNull(CodeBlock valueToCheck, CodeBlock code) {
        return CodeBlock
                .builder()
                .beginControlFlow("if ($L != null)", valueToCheck)
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
                .beginControlFlow("for (var $L : $N)", namedIteratorPrefix(variable), inputPrefix(variable))
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
                .beginControlFlow("for (int $1L = 0; $1N < $2N.size(); $1N++)", namedIndexIteratorPrefix(variable), inputPrefix(variable))
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
    public static CodeBlock wrapRowOfMap(CodeBlock code) {
        return CodeBlock.ofIf(!code.isEmpty(), "$T.rowOfMap($L)", QUERY_HELPER.className, indentIfMultiline(code));
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a jOOQ coalesce.
     */
    @NotNull
    public static CodeBlock wrapCoalesce(CodeBlock code) {
        return CodeBlock.ofIf(!code.isEmpty(), "$T.coalesce($L)", DSL.className, indentIfMultiline(code));
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a call to SelectionSet#ifRequested.
     */
    @NotNull
    public static CodeBlock wrapSelectIfRequested(String path, CodeBlock code) {
        return CodeBlock.of("$N.ifRequested($S, () -> $L)", VAR_SELECT, path, indentIfMultiline(code));
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

    public static @NotNull CodeBlock defaultValue(CodeBlock field) {
        return CodeBlock.of("$T.defaultValue($L)", DSL.className, field);
    }

    public static @NotNull CodeBlock defaultValue(String tableName, String fieldName) {
        return defaultValue(tableFieldCodeBlock(tableName, fieldName));
    }

    public static CodeBlock tableFieldCodeBlock(String targetTable, String column) {
        return tableFieldCodeBlock(CodeBlock.of("$N", targetTable), column);
    }

    public static CodeBlock tableFieldCodeBlock(CodeBlock targetTable, String column) {
        return CodeBlock.of("$L.$L", targetTable, column);
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
     * @return CodeBlock that converts a SQL array field to a Java List using jOOQ's convertFrom with null-safety.
     */
    public static CodeBlock arrayToListConverter() {
        return CodeBlock.of(".convertFrom($T.nullOnAllNull($N -> $T.of($L)))",
                FUNCTIONS.className, VAR_ITERATOR, LIST.className, VAR_ITERATOR);
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
                listOf(CodeBlock.join(", ", fromBlocks)),
                listOf(CodeBlock.join(", ", toBlocks))
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
                listedOutputPrefix(recordName),
                wrapArrayList(recordTypeName),
                ClassName.get(transform.getDeclaringClass()),
                transform.getName(),
                VAR_CONTEXT,
                listedOutputPrefix(recordName)
        );
    }

    public static CodeBlock fetchMapping(boolean iterable) {
        return iterable
                ? CodeBlock.of("$1L -> $1N.value1().intoMap(), $1L -> $1N.value2().intoMap()", VAR_ITERATOR)
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
     * Returns condition for filtering on resolver key.
     */
    public static CodeBlock inResolverKeysBlock(String resolverKeyParamName, FetchContext context) {
        return CodeBlock.of(
                "$1L.in($2N.stream().map($3N -> $3N.key().valuesRow()).toList())",
                wrapRow(commaSeparatedKeyFields(context.getResolverKey().key(), context.getTargetTableName(), context.getTargetAlias())),
                resolverKeyParamName,
                VAR_ITERATOR
        );
    }

    /**
     * Returns code for selecting resolver key and converting it into a table record.
     */
    public static CodeBlock resolverKeyAsTableRecord(FetchContext context) {
        return keyAsTableRecordWithQueryHelper(context.getResolverKey().key(), context.getTargetTableName(), context.getTargetAlias());
    }

    /**
     * Returns a CodeBlock that selects key fields as a row and converts fetched records into TableRecords
     * via {@link no.sikt.graphql.helpers.query.QueryHelper#intoTableRecord}.
     */
    public static CodeBlock keyAsTableRecordWithQueryHelper(Key<?> key, String tableName, String aliasVariableName) {
        CodeBlock keyFields = commaSeparatedKeyFields(key, tableName, aliasVariableName);
        return CodeBlock.builder()
                .add(wrapRow(keyFields))
                .add(".convertFrom($1N -> $2T.intoTableRecord($1N, $3L))", VAR_ITERATOR, QUERY_HELPER.className, listOf(keyFields))
                .build();
    }

    public static CodeBlock commaSeparatedResolverKeyFields(FetchContext context) {
        return commaSeparatedKeyFields(context.getResolverKey().key(), context.getTargetTableName(), context.getTargetAlias());
    }

    private static CodeBlock commaSeparatedKeyFields(Key<?> key, String tableName, String aliasVariableName) {
        return getJavaFieldNamesForKey(tableName, key)
                .stream()
                .map(it -> CodeBlock.of("$N.$L", aliasVariableName, it))
                .collect(CodeBlock.joining(", "));
    }

    public static CodeBlock getPrimaryKeyFieldsWithTableAliasBlock(String targetAlias) {
        return CodeBlock.of("$N.fields($L)", targetAlias, getPrimaryKeyFieldsBlock(targetAlias));
    }

    public static CodeBlock getPrimaryKeyFieldsWithTableAliasBlock(String targetAlias, String direction) {
        var sortOrder = direction.equalsIgnoreCase("ASC")
                ? CodeBlock.of("$T.ASC", SORT_ORDER.className)
                : CodeBlock.of("$T.DESC", SORT_ORDER.className);
        return getPrimaryKeyFieldsWithTableAliasBlock(targetAlias, sortOrder);
    }

    public static CodeBlock getPrimaryKeyFieldsWithTableAliasBlock(String targetAlias, CodeBlock sortOrder) {
        return CodeBlock.of(
                "$T.of($N.fields($L)).map(f -> f.sort($L)).toArray($T[]::new)",
                STREAM.className, targetAlias, getPrimaryKeyFieldsBlock(targetAlias),
                sortOrder, SORT_FIELD.className);
    }

    private static @NotNull CodeBlock getPrimaryKeyFieldsBlock(String target) {
        return CodeBlock.of("$L.getPrimaryKey().getFieldsArray()", target);
    }

    public static CodeBlock ofTernary(CodeBlock ifExpr, CodeBlock thenExpr, CodeBlock elseExpr) {
        return CodeBlock.of("$L ? $L : $L", ifExpr, thenExpr, elseExpr);
    }

    public static CodeBlock staticTableInstanceBlock(String tableName) {
        var tableClass = getTableByJavaFieldName(tableName)
                .orElseThrow(() -> new RuntimeException("Unknown table " + tableName))
                .getClass();
        return CodeBlock.of("$T.$N", tableClass, tableName);
    }


    public static CodeBlock createNodeIdBlock(NodeConfiguration nodeConfiguration, String targetAlias) {
        return createNodeIdBlock(
                CodeBlock.of("$S, $L",
                        nodeConfiguration.typeId(),
                        nodeConfiguration.nodeIdFieldsWithTableVariableBlock(targetAlias)
                )
        );
    }

    public static CodeBlock createNodeIdBlockForRecord(NodeConfiguration nodeConfiguration, String recordVariableName) {
        return createNodeIdBlock(
                CodeBlock.of("$N, $S, $L",
                        recordVariableName,
                        nodeConfiguration.typeId(),
                        nodeConfiguration.nodeIdFieldsWithStaticTableInstanceBlock()
                )
        );
    }

    private static CodeBlock createNodeIdBlock(CodeBlock args) {
        return CodeBlock.of("$N.createId($L)", VAR_NODE_STRATEGY, args);
    }


    public static CodeBlock hasNodeIdOrIdsBlock(CodeBlock idOrRecordVariable, NodeConfiguration nodeConfiguration, String targetAlias, boolean isMultiple) {
        return hasNodeIdOrIdsBlock(
                nodeConfiguration.typeId(),
                idOrRecordVariable,
                nodeConfiguration.nodeIdFieldsWithTableVariableBlock(targetAlias),
                isMultiple
        );
    }

    public static CodeBlock hasNodeIdOrIdsBlock(CodeBlock idOrRecordVariable, NodeConfiguration nodeConfiguration, String targetAlias, List<String> overrideKeyCols, boolean isMultiple) {
        return hasNodeIdOrIdsBlock(
                nodeConfiguration.typeId(),
                idOrRecordVariable,
                tableFieldsBlock(CodeBlock.of("$N", targetAlias), overrideKeyCols),
                isMultiple
        );
    }

    private static CodeBlock hasNodeIdOrIdsBlock(String typeId, CodeBlock idOrRecordVariable, CodeBlock keyColumns, boolean isMultiple) {
        var args = CodeBlock.of("$S, $L, $L", typeId, idOrRecordVariable, keyColumns);
        return isMultiple ? hasNodeIdsBlock(args) : hasNodeIdBlock(args);
    }

    private static CodeBlock hasNodeIdBlock(CodeBlock code) {
        return CodeBlock.of("$N.hasId($L)", VAR_NODE_STRATEGY, code);
    }

    private static CodeBlock hasNodeIdsBlock(CodeBlock code) {
        return CodeBlock.of("$N.hasIds($L)", VAR_NODE_STRATEGY, code);
    }

    /**
     * @return CodeBlock with comma-separated table fields using a static table instance resolved from the table name.
     * Example: {@code Film.FILM.FILM_ID, Film.FILM.LANGUAGE_ID}
     */
    public static CodeBlock tableFieldsWithStaticTableInstanceBlock(String tableJavaName, List<String> fieldJavaNames) {
        return tableFieldsBlock(staticTableInstanceBlock(tableJavaName), fieldJavaNames);
    }

    /**
     * @param tableRef The CodeBlock with the table reference. For example a table alias variable, or a static table instance block.
     * @return CodeBlock with comma-separated table fields from the given table reference code block.
     */
    public static CodeBlock tableFieldsBlock(CodeBlock tableRef, List<String> fieldJavaNames) {
        return fieldJavaNames.stream()
                .map(it -> tableFieldCodeBlock(tableRef, it))
                .collect(CodeBlock.joining(", "));
    }

    /**
     * Returns a CodeBlock that extracts the primary key from a TableRecord and maps it into a new record of the same type.
     * The resulting record will only have its key fields populated.
     * <p>Example output: {@code myTableRecord.key().into(MyTableRecord.class)}
     *
     * @param variableName variable name of the table record to extract the key from.
     * @param recordClass  the record class to map the key into. This should match the record class of the variable.
     * @return CodeBlock that converts a table record into a key-only record of the same type.
     */
    public static CodeBlock extractKeyAsTableRecord(String variableName, TypeName recordClass) {
        return CodeBlock.of("$N.key().into($T.class)", variableName, recordClass);
    }
}
