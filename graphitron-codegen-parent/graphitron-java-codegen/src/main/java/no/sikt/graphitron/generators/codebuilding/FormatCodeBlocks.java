package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.TransformScope;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.objects.ConnectionObjectDefinition;
import no.sikt.graphitron.definitions.objects.EnumDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DBClassGenerator;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
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

import static no.sikt.graphitron.generators.codebuilding.MappingCodeBlocks.idFetchAllowingDuplicates;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.ResolverKeyHelpers.getKeyTypeName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphql.naming.GraphQLReservedName.ERROR_FIELD;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Class containing various helper methods for constructing code with javapoet.
 */
public class FormatCodeBlocks {
    private final static CodeBlock
            COLLECT_TO_LIST = CodeBlock.of(".toList()"),
            NEW_TRANSFORM = CodeBlock.of("new $T($N)", RECORD_TRANSFORMER.className, VARIABLE_ENV),
            DECLARE_TRANSFORM = declare(TRANSFORMER_NAME, NEW_TRANSFORM),
            NEW_DATA_FETCHER = CodeBlock.of("new $T($N)", DATA_FETCHER_HELPER.className, VARIABLE_ENV),
            NEW_SERVICE_DATA_FETCHER_TRANSFORM = CodeBlock.of("new $T<>($N)", DATA_SERVICE_FETCHER.className, TRANSFORMER_NAME),
            ATTACH = CodeBlock.of(".attach($N.configuration())", CONTEXT_NAME),
            ATTACH_RESOLVER = CodeBlock.of(".attach($L.configuration())", asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME)),
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

        var code = CodeBlock.builder().add(declare(name, input.getRecordClassName(), isIterable));
        if (!input.hasJavaRecordReference() && !isIterable) {
            code.addStatement("$N$L", name, isResolver ? ATTACH_RESOLVER : ATTACH);
        }
        return code.build();
    }

    public static CodeBlock recordTransformPart(String transformerName, String varName, String typeName, boolean isJava, boolean isInput) {
        return CodeBlock.of("$N.$L($N, ", transformerName, recordTransformMethod(typeName, isJava, isInput), uncapitalize(varName));
    }

    /**
     * @param name Name of the variable.
     * @param typeName The type of the variable to declare.
     * @param asList Declare this type as an ArrayList?
     * @return CodeBlock that declares a simple variable.
     */
    public static CodeBlock declare(String name, TypeName typeName, boolean asList) {
        if (asList) {
            return declare(asListedName(name), ParameterizedTypeName.get(ARRAY_LIST.className, typeName));
        }
        return declare(name, typeName);
    }

    /**
     * @param name Name of the variable.
     * @param block The statement result to declare.
     * @return CodeBlock that declares a simple variable.
     */
    public static CodeBlock declare(String name, CodeBlock block) {
        return CodeBlock.builder().addStatement("var $L = $L", uncapitalize(name), block).build();
    }

    /**
     * @param name Name of the variable.
     * @param type The type to declare.
     * @return CodeBlock that declares a simple variable.
     */
    public static CodeBlock declare(String name, TypeName type) {
        return CodeBlock.builder().addStatement("var $L = new $T()", uncapitalize(name), type).build();
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
        return CodeBlock.builder().addStatement("$N.add($N)", addTarget, addition).build();
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
        return CodeBlock.builder().addStatement("$N.add($L)", addTarget, codeAddition).build();
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
        if (code.isEmpty()) {
            return CodeBlock.empty();
        }
        return CodeBlock.of("$T.of($L)", LIST.className, code);
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
        return CodeBlock.of("$T.of($L)", SET.className, code);
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
    public static CodeBlock setValue(String container, MethodMapping mapping, CodeBlock value) {
        return CodeBlock.of("$N$L", uncapitalize(container), mapping.asSetCall(value));
    }

    /**
     * @return CodeBlock that sets a value through a mapping.
     */
    @NotNull
    public static CodeBlock setValue(String container, MethodMapping mapping, String value) {
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
        return CodeBlock.builder().addStatement("if ($N == null) continue", value).build();
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
                includeContext ? CodeBlock.of("$L, ", CONTEXT_NAME) : CodeBlock.empty(),
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

        return CodeBlock.of(
                isService ? "($L) -> $N.$L($L)" : "($L) -> $T.$L($L)",
                String.join(", ", inputs),
                isService ? uncapitalize(queryLocation) : getQueryClassName(queryLocation),
                queryMethodName,
                String.join(", ", params));
    }

    /**
     * @return CodeBlock for a function that maps relay connection types.
     */
    public static CodeBlock connectionFunction(ConnectionObjectDefinition connectionType, ObjectDefinition pageInfoType) {
        return CodeBlock
                .builder()
                .beginControlFlow("($L) -> ", CONNECTION_NAME)
                .add(
                        declare(
                                EDGES_NAME,
                                CodeBlock.of(
                                        "$N.getEdges().stream().map(it -> new $T($L.getValue(), it.getNode()))$L",
                                        CONNECTION_NAME,
                                        connectionType.getEdgeObject().getGraphClassName(),
                                        nullIfNullElseThis(CodeBlock.of("it.getCursor()")),
                                        collectToList()
                                )
                        )
                )
                .add(declare(PAGE_NAME, CodeBlock.of("$N.getPageInfo()", CONNECTION_NAME)))
                .add(
                        declare(
                                GRAPH_PAGE_NAME,
                                CodeBlock.of(
                                        "new $T($N.isHasPreviousPage(), $N.isHasNextPage(), $L.getValue(), $L.getValue())",
                                        pageInfoType.getGraphClassName(),
                                        PAGE_NAME,
                                        PAGE_NAME,
                                        nullIfNullElseThis(CodeBlock.of("$N.getStartCursor()", PAGE_NAME)),
                                        nullIfNullElseThis(CodeBlock.of("$N.getEndCursor()", PAGE_NAME))
                                )
                        )
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
                field.isIterableWrapped() ? CodeBlock.empty() : CodeBlock.of(".values()$L.orElse(null)", findFirst())
        );
    }

    /**
     * @return CodeBlock consisting of a declaration of the page size variable through a method call.
     */
    @NotNull
    public static CodeBlock declarePageSize(int defaultFirst) {
        return CodeBlock.builder().addStatement(
                "int $L = $T.getPageSize($N, $L, $L)",
                PAGE_SIZE_NAME,
                RESOLVER_HELPERS.className,
                GraphQLReservedName.PAGINATION_FIRST.getName(),
                GeneratorConfig.getMaxAllowedPageSize(),
                defaultFirst
        ).build();
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
        var indexName = asIndexName(asIterable(variable));
        return CodeBlock
                .builder()
                .beginControlFlow("for (int $L = 0; $N < $N.size(); $N++)", indexName, indexName, variable, indexName)
                .add(code)
                .endControlFlow()
                .build();
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a jOOQ row.
     */
    @NotNull
    public static CodeBlock wrapRow(CodeBlock code) {
        if (code.isEmpty()) {
            return CodeBlock.empty();
        }
        return CodeBlock.of("$T.row($L)", DSL.className, indentIfMultiline(code));
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a jOOQ row.
     */
    @NotNull
    public static CodeBlock wrapObjectRow(CodeBlock code) {
        if (code.isEmpty()) {
            return CodeBlock.empty();
        }
        return CodeBlock.of("$T.objectRow($L)", QUERY_HELPER.className, indentIfMultiline(code));
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a jOOQ coalesce.
     */
    @NotNull
    public static CodeBlock wrapCoalesce(CodeBlock code) {
        if (code.isEmpty()) {
            return CodeBlock.empty();
        }
        return CodeBlock.of("$T.coalesce($L)", DSL.className, indentIfMultiline(code));
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a jOOQ inline.
     */
    @NotNull
    public static CodeBlock inline(CodeBlock code) {
        if (code.isEmpty()) {
            return CodeBlock.empty();
        }

        return CodeBlock.of("$T.inline($L)", DSL.className, code);
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
        return CodeBlock.of("$T.makeEnumMap($N, $L)", QUERY_HELPER.className, inputVariable, valueLists);
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
     * @param recordName Name of the record to transform.
     * @param scope      The scope of transforms that should be applied. Currently only {@link TransformScope#ALL_MUTATIONS} is supported.
     * @return CodeBlock where all defined global transforms are applied to the record.
     */
    public static CodeBlock applyGlobalTransforms(String recordName, TypeName recordTypeName, TransformScope scope) {
        var code = CodeBlock.builder();
        GeneratorConfig
                .getGlobalTransforms(scope)
                .stream()
                .map(it -> getMethodFrom(it.getFullyQualifiedClassName(), it.getMethod()))
                .forEach(transform -> code.add(applyTransform(recordName, recordTypeName, transform)));
        return code.build();
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
        var declaringClass = transform.getDeclaringClass();
        return CodeBlock.builder().addStatement(
                "$N = ($T) $T.$L($N, $N)",
                asListedName(recordName),
                ParameterizedTypeName.get(ARRAY_LIST.className, recordTypeName),
                ClassName.get(declaringClass),
                transform.getName(),
                CONTEXT_NAME,
                asListedName(recordName)
        ).build();
    }

    /**
     * @return Code for constructing any structure of response types.
     */
    public static CodeBlock makeResponses(MapperContext context, ObjectField field, ProcessedSchema schema, InputParser parser) {
        var target = context.getTarget();
        if (!context.targetIsType() || schema.isExceptionOrExceptionUnion(target)) {
            return CodeBlock.empty();
        }

        var targetTypeName = target.getTypeName();
        var object = context.getTargetType();
        var record = findUsableRecord(target, schema, parser);
        var wrapInFor = (!context.isTopLevelContext() || target.isIterableWrapped()) && record.isIterableWrapped();
        var code = CodeBlock
                .builder()
                .add("\n")
                .add(declare(targetTypeName, object.getGraphClassName()));
        var filteredFields = object
                .getFields()
                .stream()
                .filter(it -> !it.getMappingFromFieldOverride().getName().equalsIgnoreCase(ERROR_FIELD.getName()))
                .toList(); //TODO tmp solution to skip mapping Errors as this is handled by "MutationExceptionStrategy"
        for (var innerField : filteredFields) {
            var innerContext = context.iterateContext(innerField);
            if (!innerContext.targetIsType()) {
                if (innerField.isID()) {
                    code
                            .beginControlFlow("if ($L)", selectionSetLookup(innerContext.getPath(), true, false))
                            .add(innerContext.getSetMappingBlock(getIDMappingCode(innerContext, field, schema, parser)))
                            .endControlFlow()
                            .add("\n");
                }
                continue;
            }

            var innerCode = CodeBlock.builder();
            var recordField = findUsableRecord(innerField, schema, parser); // In practice this supports only one record type at once. Can't map to types that are not records.
            var recordName = asIterableIf(asListedRecordNameIf(recordField.getName(), recordField.isIterableWrapped()), wrapInFor);
            if (schema.implementsNode(innerField)) {
                innerCode.add(idFetchAllowingDuplicates(innerContext, innerField, recordName, true));
            } else {
                innerCode.add(makeResponses(innerContext, field, schema, parser));
                var inputSource = !innerContext.getTargetType().hasTable()
                        ? innerField.getTypeName()
                        : asGetMethodVariableName(asRecordName(recordField.getName()), innerField.getName());

                var recordIterable = recordField.isIterableWrapped();
                if (recordIterable == innerContext.isIterable()) {
                    innerCode.add(innerContext.getSetMappingBlock(asListedNameIf(inputSource, innerContext.isIterable())));
                } else if (!recordIterable && innerContext.isIterable()) {
                    innerCode.add(innerContext.getSetMappingBlock(listOf(uncapitalize(inputSource))));
                } else {
                    innerCode.add(innerContext.getSetMappingBlock(CodeBlock.of("$N$L.orElse($L)", asListedName(inputSource), findFirst(), listOf())));
                }
            }

            code
                    .beginControlFlow("if ($N != null && $L)", recordName, selectionSetLookup(innerContext.getPath(), true, false))
                    .add(innerCode.build())
                    .endControlFlow()
                    .add("\n");
        }

        if (!wrapInFor) {
            return code.build();
        }

        return CodeBlock
                .builder()
                .add(declare(targetTypeName, object.getGraphClassName(), true))
                .add(wrapFor(asListedRecordName(record.getName()), code.add(addToList(targetTypeName)).build()))
                .build();
    }

    public static CodeBlock getIDMappingCode(MapperContext context, ObjectField field, ProcessedSchema schema, InputParser parser) {
        var inputSource = field
                .getArguments()
                .stream()
                .filter(InputField::isID)
                .findFirst();

        boolean isIterable = context.isIterable(), shouldMap = true;
        String idSource;
        if (inputSource.isPresent()) {
            var source = inputSource.get();
            isIterable = source.isIterableWrapped();
            shouldMap = isIterable || schema.isInputType(source);
            idSource = source.getName();
        } else {
            var previousField = context.isTopLevelContext() ? context.getTarget() : context.getPreviousContext().getTarget();
            var recordSource = parser
                    .getJOOQRecords()
                    .entrySet()
                    .stream()
                    .filter(it -> schema.getInputType(it.getValue()).getFields().stream().anyMatch(InputField::isID))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Could not find a suitable ID to return for '" + previousField.getName() + "'."));
            idSource = asIterableIf(recordSource.getKey(), previousField.isIterableWrapped() && schema.isObject(previousField));
        }

        var code = CodeBlock.builder().add("$N", idSource);
        if (shouldMap) {
            if (isIterable) {
                code.add(".stream().map(it -> it.getId())$L", collectToList());
            } else {
                code.add(".getId()");
            }
        }

        return code.build();
    }

    private static InputField findUsableRecord(GenerationField target, ProcessedSchema schema, InputParser parser) {
        var responseObject = schema.getObject(target);
        if (responseObject.hasTable()) {
            var responseFieldTableName = responseObject.getTable().getMappingName();
            return parser
                    .getJOOQRecords()
                    .values()
                    .stream()
                    .filter(it -> schema.getInputType(it).getTable().getMappingName().equals(responseFieldTableName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Could not find an appropriate record to map table reference '" + responseFieldTableName + "' to."));
        }
        return parser
                .getJOOQRecords()
                .entrySet()
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Could not find an appropriate record to map table references to."))
                .getValue(); // In practice this supports only one record type at once.
    }

    public static CodeBlock fetchMapping(boolean iterable) {
        return iterable
                ? CodeBlock.of("$1L -> $1N.value1().intoMap(), $1L -> $1N.value2().intoMap()", VARIABLE_INTERNAL_ITERATION)
                : CodeBlock.of(".fetchOneMap()");
    }

    public static CodeBlock declareArgs(ObjectField target) {
        return target.getArguments().isEmpty() ? CodeBlock.empty() : declare(VARIABLE_ARGS, asMethodCall(VARIABLE_ENV, METHOD_ARGS_NAME));
    }

    /**
     * @return CodeBlock that returns the provided name.
     */
    @NotNull
    public static CodeBlock returnWrap(String variable) {
        return CodeBlock.builder().addStatement("return $N", variable).build();
    }

    /**
     * @return CodeBlock that returns the provided code.
     */
    @NotNull
    public static CodeBlock returnWrap(CodeBlock code) {
        return CodeBlock.builder().addStatement("return $L", code).build();
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
        return CodeBlock.of("$L.in($N.stream().map($T::valuesRow).toList())",
                getSelectKeyColumnRow(context),
                resolverKeyParamName,
                getKeyTypeName(context.getResolverKey(), false)
        );
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
                getJavaFieldNamesForKey(tableName, key)
                        .stream()
                        .map(it -> CodeBlock.of("$N.$L", aliasVariableName, it))
                        .collect(CodeBlock.joining(", "))
        );
    }

    /**
     * Returns codeblock for selecting key columns for the resolver key
     * @param context The fetching context
     * @return Select code for the columns in the resolver key
     */
    public static CodeBlock getSelectKeyColumnRow(FetchContext context) {
        return getSelectKeyColumnRow(context.getResolverKey(), context.getTargetTableName(), context.getTargetAlias());
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

    public static CodeBlock hasIdBlock(CodeBlock id, ObjectDefinition obj, String targetAlias) {
        return hasIdOrIdsBlock(id, obj, targetAlias, CodeBlock.empty(), false);
    }

    public static CodeBlock hasIdsBlock(ObjectDefinition obj, String targetAlias) {
        return hasIdOrIdsBlock(CodeBlock.of(IDS_NAME), obj, targetAlias, CodeBlock.empty(), true);
    }

    public static CodeBlock hasIdOrIdsBlock(CodeBlock idOrRecordParamName, ObjectDefinition obj, String targetAlias, CodeBlock mappedFkFields, boolean isMultiple) {
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
            return obj.getKeyColumns().stream().map(it -> CodeBlock.of("$N.$L", staticTableInstanceBlock(obj.getTable().getName()), it))
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
}
