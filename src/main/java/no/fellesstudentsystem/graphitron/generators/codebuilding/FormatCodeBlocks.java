package no.fellesstudentsystem.graphitron.generators.codebuilding;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.TransformScope;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.objects.ConnectionObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.EnumDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.ArrayList;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Class containing various helper methods for constructing code with javapoet.
 */
public class FormatCodeBlocks {
    private final static CodeBlock
            COLLECT_TO_LIST = CodeBlock.of(".collect($T.toList())", COLLECTORS.className),
            NEW_TRANSFORM = CodeBlock.of("new $T($N, this.$N)", RECORD_TRANSFORMER.className, VARIABLE_ENV, CONTEXT_NAME),
            DECLARE_TRANSFORM = declare(TRANSFORMER_NAME, NEW_TRANSFORM),
            NEW_DATA_FETCHER = CodeBlock.of("new $T($N, this.$N)", DATA_FETCHER.className, VARIABLE_ENV, CONTEXT_NAME),
            NEW_SERVICE_DATA_FETCHER_TRANSFORM = CodeBlock.of("new $T<>($N)", DATA_SERVICE_FETCHER.className, TRANSFORMER_NAME),
            ATTACH = CodeBlock.of(".attach($N.configuration())", CONTEXT_NAME),
            FIND_FIRST = CodeBlock.of(".stream().findFirst()"),
            EMPTY_LIST = CodeBlock.of("$T.of()", LIST.className),
            EMPTY_SET = CodeBlock.of("$T.of()", SET.className),
            EMPTY_MAP = CodeBlock.of("$T.of()", MAP.className),
            EMPTY_BLOCK = CodeBlock.builder().build();
    private final static String CONNECTION_NAME = "connection", PAGE_NAME = "page", EDGES_NAME = "edges", GRAPH_PAGE_NAME = "graphPage";

    /**
     * @param variableName The name of the ArrayList variable.
     * @param typeName The parameter type of the ArrayList to declare.
     * @return CodeBlock that declares a new ArrayList variable.
     */
    @NotNull
    public static CodeBlock declareArrayList(String variableName, TypeName typeName) {
        return declareVariable(variableName, typeName, true);
    }

    /**
     * @param name Name of a field that should be declared as a record. This will be the name of the variable.
     * @param input Input type that should be declared as a record.
     * @param isIterable Is this record wrapped in a list?
     * @return CodeBlock that declares a new record variable and that attaches context configuration if needed.
     */
    public static CodeBlock declareRecord(String name, RecordObjectSpecification<?> input, boolean isIterable) {
        if (!input.hasRecordReference()) {
            return empty();
        }

        var code = CodeBlock.builder().add(declareVariable(name, input.getRecordClassName(), isIterable));
        if (!input.hasJavaRecordReference() && !isIterable) {
            code.addStatement("$N$L", name, ATTACH);
        }
        return code.build();
    }

    /**
     * @return CodeBlock for the mapping of a record.
     */
    public static CodeBlock transformRecord(String initialName, String typeName, boolean isJava) {
        return CodeBlock.of(
                "$L\"$L\"$L)",
                recordTransformPart(initialName, typeName, isJava, true),
                initialName,
                recordValidationEnabled() && !isJava ? CodeBlock.of(", \"$L\"", initialName) : empty()
        );
    }

    /**
     * @return CodeBlock for the mapping of a record. Includes path for validation.
     */
    public static CodeBlock transformRecord(String varName, String typeName, String path, String pathWithIndex, boolean isJava) {
        return CodeBlock.of(
                "$L\"$L\"$L)",
                recordTransformPart(varName, typeName, isJava, true),
                path,
                recordValidationEnabled() && !isJava ? CodeBlock.of(", \"$L\"", pathWithIndex) : empty()
        );
    }

    /**
     * @return CodeBlock for the mapping of a record.
     */
    public static CodeBlock transformRecord(String varName, String typeName, String path, boolean isJava, boolean isInput) {
        return CodeBlock.of(
                "$L$N + $S$L)",
                recordTransformPart(varName, typeName, isJava, isInput),
                PATH_HERE_NAME,
                path,
                recordValidationEnabled() && !isJava && isInput ? CodeBlock.of(", $N + $S", PATH_HERE_NAME, path) : empty() // This one may need more work. Does not actually include indices here, but not sure if needed.
        );
    }

    /**
     * @return CodeBlock for the mapping of a record.
     */
    public static CodeBlock transformRecord(String varName, String typeName, String path, boolean isJava) {
        return CodeBlock.of("$L$S)", recordTransformPart(varName, typeName, isJava, false), path);
    }

    private static CodeBlock recordTransformPart(String varName, String typeName, boolean isJava, boolean isInput) {
        return CodeBlock.of("$N.$L($N, ", TRANSFORMER_NAME, recordTransformMethod(typeName, isJava, isInput), varName);
    }

    /**
     * @param name Name of the variable.
     * @param typeName The type of the variable to declare.
     * @return CodeBlock that declares a simple variable.
     */
    public static CodeBlock declareVariable(String name, TypeName typeName) {
        return declareVariable(name, typeName, false);
    }

    /**
     * @param name Name of the variable.
     * @param typeName The type of the variable to declare.
     * @param asList Declare this type as an ArrayList?
     * @return CodeBlock that declares a simple variable.
     */
    public static CodeBlock declareVariable(String name, TypeName typeName, boolean asList) {
        if (asList) {
            return declare(asListedName(name), CodeBlock.of("new $T<$T>()", ARRAY_LIST.className, typeName));
        }
        return declare(uncapitalize(name), CodeBlock.of("new $T()", typeName));
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
     * @return CodeBlock that contains an if statement with a null check on the provided name.
     */
    @NotNull
    public static CodeBlock ifNotNull(String name) {
        return CodeBlock.of("if ($N != null)", name);
    }

    /**
     * @return empty CodeBlock
     */
    public static CodeBlock empty() {
        return EMPTY_BLOCK;
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
     * @return CodeBlock that wraps the supplied CodeBlock in a Map.
     */
    @NotNull
    public static CodeBlock mapOf(CodeBlock code) {
        return CodeBlock.of("$T.of($L)", MAP.className, code);
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
     * @return CodeBlock that wraps the provided variable name in a simple null check.
     */
    @NotNull
    public static CodeBlock nullIfNullElse(String variable) {
        return CodeBlock.of("$N == null ? null : ", variable);
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock name in a simple null check.
     */
    @NotNull
    public static CodeBlock nullIfNullElse(CodeBlock code) {
        return CodeBlock.of("$L == null ? null : ", code);
    }

    /**
     * @return CodeBlock that wraps the provided variable name in a simple null check.
     */
    @NotNull
    public static CodeBlock nullIfNullElseThis(String variable) {
        return CodeBlock.of("$L$N", nullIfNullElse(variable), variable);
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock name in a simple null check.
     */
    @NotNull
    public static CodeBlock nullIfNullElseThis(CodeBlock code) {
        return CodeBlock.of("$L$L", nullIfNullElse(code), code);
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
            return CodeBlock.of("$N.contains($N + $S)", useArguments ? VARIABLE_ARGUMENTS : VARIABLE_SELECT, PATH_HERE_NAME, path);
        }
        return CodeBlock.of("$L.contains($S)", asMethodCall(TRANSFORMER_NAME, useArguments ? TransformerClassGenerator.METHOD_ARGS_NAME : TransformerClassGenerator.METHOD_SELECT_NAME), path);
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
     * @return CodeBlock that creates a resolver transformer.
     */
    @NotNull
    public static CodeBlock newTransform() {
        return NEW_TRANSFORM;
    }

    /**
     * @return CodeBlock that creates a data fetcher object.
     */
    @NotNull
    public static CodeBlock newDataFetcher() {
        return NEW_DATA_FETCHER;
    }

    /**
     * @return CodeBlock that creates a transform through a data fetcher object.
     */
    @NotNull
    public static CodeBlock newDataFetcherWithTransform() {
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
        if (!inputList.isEmpty()) {
            params.add(inputList);
        }

        var queryClass = ClassName.get(GeneratorConfig.outputPackage() + "." + DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + FetchDBClassGenerator.SAVE_DIRECTORY_NAME, queryLocation);
        return CodeBlock.of(
                isService ? "($L$L) -> $L.count$L($L)" : "($L$L) -> $T.count$L($L)",
                includeContext ? CodeBlock.of("$L, ", CONTEXT_NAME) : empty(),
                IDS_NAME,
                isService ? uncapitalize(queryLocation) : queryClass,
                capitalize(queryMethodName),
                String.join(", ", params)
        );
    }

    /**
     * @return CodeBlock consisting of a function for a generic DB call.
     */
    @NotNull
    public static CodeBlock queryFunction(String queryLocation, String queryMethodName, String inputList, boolean hasIds, boolean usesIds, boolean isService) {
        var inputs = new ArrayList<String>();
        var params = new ArrayList<String>();
        if (!isService) {
            inputs.add(CONTEXT_NAME);
            params.add(CONTEXT_NAME);
        }
        if (hasIds) {
            inputs.add(IDS_NAME);
        }
        if (usesIds) {
            params.add(IDS_NAME);
        }
        if (!inputList.isEmpty()) {
            params.add(inputList);
        }
        if (!isService) {
            inputs.add(SELECTION_SET_NAME);
            params.add(SELECTION_SET_NAME);
        }

        var queryClass = ClassName.get(GeneratorConfig.outputPackage() + "." + DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + FetchDBClassGenerator.SAVE_DIRECTORY_NAME, queryLocation);
        return CodeBlock.of(
                isService ? "($L) -> $N.$L($L)" : "($L) -> $T.$L($L)",
                String.join(", ", inputs),
                isService ? uncapitalize(queryLocation) : queryClass,
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
                                        "$N.getEdges().stream().map(it -> $T.builder().setCursor($L.getValue()).setNode(it.getNode()).build())$L",
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
                                        "$T.builder().setStartCursor($L.getValue()).setEndCursor($L.getValue()).setHasNextPage($N.isHasNextPage()).setHasPreviousPage($N.isHasPreviousPage()).build()",
                                        pageInfoType.getGraphClassName(),
                                        nullIfNullElseThis(CodeBlock.of("$N.getStartCursor()", PAGE_NAME)),
                                        nullIfNullElseThis(CodeBlock.of("$N.getEndCursor()", PAGE_NAME)),
                                        PAGE_NAME,
                                        PAGE_NAME
                                )
                        )
                )
                .add(
                        returnWrap(
                                CodeBlock.of(
                                        "$T.builder().setNodes($N.getNodes()).setEdges($N).setTotalCount($N.getTotalCount()).setPageInfo($N).build()",
                                        connectionType.getGraphClassName(),
                                        CONNECTION_NAME,
                                        EDGES_NAME,
                                        CONNECTION_NAME,
                                        GRAPH_PAGE_NAME
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
    public static CodeBlock getNodeQueryCallBlock(GenerationField field, String variableName, CodeBlock path, boolean useExtraGetLayer, boolean isIterable, boolean atResolver) {
        var typeName = field.getTypeName();
        var idCall = useExtraGetLayer ? CodeBlock.of("$L.getId()", field.getMappingForRecordFieldOverride().asGetCall()) : CodeBlock.of(".getId()");
        var queryClass = ClassName.get(GeneratorConfig.outputPackage() + "." + DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + FetchDBClassGenerator.SAVE_DIRECTORY_NAME, asQueryClass(typeName));
        return CodeBlock.of(
                "$T.$L($N, $L, $L.withPrefix($L))$L$L",
                queryClass,
                asQueryNodeMethod(typeName),
                CONTEXT_NAME,
                isIterable ? CodeBlock.of("$N.stream().map(it -> it$L).collect($T.toSet())", variableName, idCall, COLLECTORS.className) : setOf(CodeBlock.of("$N$L", variableName, idCall)),
                atResolver ? asMethodCall(TRANSFORMER_NAME, TransformerClassGenerator.METHOD_SELECT_NAME) : CodeBlock.of("$N", VARIABLE_SELECT),
                path,
                isIterable ? empty() : CodeBlock.of(".values()$L.orElse(null)", findFirst()),
                atResolver && !isIterable ? CodeBlock.of(".values().stream()$L", collectToList()) : empty()
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
            return empty();
        }

        return CodeBlock.of("$T.row($L)", DSL.className, indentIfMultiline(code));
    }

    /**
     * @return CodeBlock that wraps the provided CodeBlock in a jOOQ inline.
     */
    @NotNull
    public static CodeBlock inline(CodeBlock code) {
        if (code.isEmpty()) {
            return empty();
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
     * @return Code block containing the enum conversion method call with an anonymous function declaration.
     */
    public static CodeBlock toJOOQEnumConverter(String enumType, boolean isIterable, ProcessedSchema schema) {
        if (!schema.isEnum(enumType)) {
            return empty();
        }

        var enumEntry = schema.getEnum(enumType);
        var tempVariableName = "s";
        return CodeBlock
                .builder()
                .add(".convert($T.class, $L -> $L, $L -> $L)",
                     enumEntry.getGraphClassName(),
                     tempVariableName,
                     toNullSafeMapCall(CodeBlock.of(tempVariableName), enumEntry, isIterable, false, true),
                     tempVariableName,
                     toNullSafeMapCall(CodeBlock.of(tempVariableName), enumEntry, isIterable, false, false)
                )
                .build();
    }

    /**
     * @return Code block containing the enum conversion method call.
     */
    public static CodeBlock toGraphEnumConverter(
            String enumType,
            CodeBlock field,
            boolean isIterable,
            boolean toRecord,
            ProcessedSchema schema) {
        if (!schema.isEnum(enumType)) {
            return empty();
        }

        var enumEntry = schema.getEnum(enumType);
        return toNullSafeMapCall(field, enumEntry, isIterable, true, !toRecord);
    }

    private  static CodeBlock toNullSafeMapCall(
            CodeBlock variable,
            EnumDefinition enumEntry,
            boolean isIterable,
            boolean isGraphConverter,
            boolean flipDirection) {
        if (isIterable && isGraphConverter) {
            var itName = asIterable(enumEntry.getName());
            return CodeBlock.of(
                    "$L.stream().map($L -> $L.getOrDefault($L, null))$L",
                    nullIfNullElseThis(variable),
                    itName,
                    mapOf(renderEnumMapElements(enumEntry, flipDirection)),
                    itName,
                    collectToList()
            );
        }

        return CodeBlock.of(
                "$L$L.getOrDefault($L, null)",
                nullIfNullElse(variable),
                mapOf(renderEnumMapElements(enumEntry, flipDirection)),
                variable
        );
    }

    private static CodeBlock renderEnumMapElements(EnumDefinition enumEntry, boolean flipDirection) {
        var code = CodeBlock.builder();
        var hasEnumReference = enumEntry.hasJavaEnumMapping();
        var enumReference = enumEntry.getEnumReference();
        var entryClassName = enumEntry.getGraphClassName();
        var entrySet = new ArrayList<>(enumEntry.getFields());
        var entrySetSize = entrySet.size();

        for (int i = 0; i < entrySetSize; i++) {
            var enumValue = entrySet.get(i);
            if (flipDirection) {
                code
                        .add(renderEnumValueSide(hasEnumReference, enumReference, enumValue.getUpperCaseName()))
                        .add(", $L", renderEnumKeySide(entryClassName, enumValue.getName()));
            } else {
                code
                        .add("$L, ", renderEnumKeySide(entryClassName, enumValue.getName()))
                        .add(renderEnumValueSide(hasEnumReference, enumReference, enumValue.getUpperCaseName()));
            }
            if (i < entrySetSize - 1) {
                code.add(", ");
            }
        }

        return code.build();
    }

    private static CodeBlock renderEnumKeySide(TypeName entryClassName, String keyName) {
        return CodeBlock.of("$T.$L", entryClassName, keyName);
    }

    private static CodeBlock renderEnumValueSide(boolean hasEnumReference, CodeReference reference, String valueName) {
        if (hasEnumReference) {
            return CodeBlock.of("$T.$L", ClassName.get(GeneratorConfig.getExternalReferences().getClassFrom(reference)), valueName);
        } else {
            return CodeBlock.of("$S", valueName);
        }
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
                .filter(it -> GeneratorConfig.getExternalReferences().contains(it.getName()))
                .map(it -> GeneratorConfig.getExternalReferences().getMethodFrom(it.getName(), it.getMethod(), false))
                .forEach(transform -> code.add(applyTransform(recordName, recordTypeName, transform)));
        return code.build();
    }

    /**
     * @param recordName Name of the record to transform.
     * @param transform  The method that should transform the record.
     * @return CodeBlock where the transform is applied to the record.
     */
    public static CodeBlock applyTransform(String recordName, TypeName recordTypeName, Method transform) {
        var declaringClass = transform.getDeclaringClass();
        return CodeBlock.builder().addStatement(
                "$N = ($T<$T>) $T.$L($N, $N)",
                asListedName(recordName),
                ARRAY_LIST.className,
                recordTypeName,
                ClassName.get(declaringClass),
                transform.getName(),
                CONTEXT_NAME,
                asListedName(recordName)
        ).build();
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
}
