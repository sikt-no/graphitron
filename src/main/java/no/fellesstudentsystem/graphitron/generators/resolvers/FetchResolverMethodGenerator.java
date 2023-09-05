package no.fellesstudentsystem.graphitron.generators.resolvers;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.QueryDependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Map.entry;
import static no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator.FILE_NAME_SUFFIX;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.returnCompletedFuture;
import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.wrapListIf;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asQueryMethodName;
import static no.fellesstudentsystem.graphitron.generators.db.FetchCountDBMethodGenerator.TOTAL_COUNT_NAME;
import static no.fellesstudentsystem.graphitron.generators.db.FetchDBClassGenerator.SAVE_DIRECTORY_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for default fetch queries with potential arguments or pagination.
 */
public class FetchResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
    private static final String
            EXECUTION_STEP_PATH_ID_DELIMITER = "||",
            BATCHED_ENV_NAME = "batchEnvLoader",
            PAGE_SIZE_NAME = "pageSize",
            AFTER_NAME = "after",
            MAP_RESULT_NAME = "mapResult",
            QUERY_RESULT_NAME = "dbResult",
            PAGINATION_RESULT_NAME = "pagedResult",
            RESULT_ENTRY_NAME = "resultEntry",
            RESULT_VALUE_NAME = "resultValue",
            PAGINATION_RESULT_ENTRY_NAME = "pagedResultEntry",
            SELECTION_SET_NAME = "selectionSet";
    private static final int MAX_NUMBER_OF_NODES_TO_DISPLAY = 1000;

    public FetchResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var localObject = getLocalObject();
        var isRootLevel = localObject.isRoot();

        var returnClassName = getReturnTypeName(target);
        var spec = getDefaultSpecBuilder(target.getName(), returnClassName);

        if (!isRootLevel) {
            spec.addParameter(localObject.getGraphClassName(), uncapitalize(localObject.getName()));
        }

        var allQueryInputs = getQueryInputs(spec, target);
        spec.addParameter(DATA_FETCHING_ENVIRONMENT.className, ENV_NAME);

        spec.addCode(queryMethodCalls(target, returnClassName, allQueryInputs));

        var currentResultName = (isRootLevel) ? QUERY_RESULT_NAME : MAP_RESULT_NAME;
        if (target.hasForwardPagination()) {
            if (!isRootLevel) {
                spec
                        .addCode("var $L = $N.entrySet().stream().map(", PAGINATION_RESULT_NAME, currentResultName)
                        .beginControlFlow("$L ->", RESULT_ENTRY_NAME)
                        .addStatement("var $L = $N.getValue()", RESULT_VALUE_NAME, RESULT_ENTRY_NAME);
                currentResultName = RESULT_VALUE_NAME;
            }
            var nodeType = processedSchema.getConnectionObject(target).getNodeType();
            var paginationInputMap = Map.ofEntries(
                    entry("math", MATH.className),
                    entry("dbResult", currentResultName),
                    entry("resultName", (!isRootLevel) ? PAGINATION_RESULT_ENTRY_NAME : PAGINATION_RESULT_NAME),
                    entry("pageSize", PAGE_SIZE_NAME),
                    entry("connectionClass", RELAY_CONNECTION_IMPL.className),
                    entry("connectionCursorClass", RELAY_CONNECTION_CURSOR_IMPL.className),
                    entry("pageInfoClass", RELAY_PAGE_INFO_IMPL.className),
                    entry("nodeType", nodeType),
                    entry("edgeClass", RELAY_EDGE_IMPL.className),
                    entry("collectors", COLLECTORS.className),
                    entry("integerClass", INTEGER.className),
                    entry("totalCount", TOTAL_COUNT_NAME),
                    entry("maxNoOfNodes", MAX_NUMBER_OF_NODES_TO_DISPLAY)
            );
            spec.addNamedCode(getPaginationSegmentString(), paginationInputMap);
            currentResultName = PAGINATION_RESULT_NAME;
            if (!isRootLevel) {
                spec
                        .addStatement(
                                "return new $T<$T, $T<$N>>($N.getKey(), $N)",
                                SIMPLE_ENTRY.className,
                                STRING.className,
                                RELAY_CONNECTION.className,
                                nodeType,
                                RESULT_ENTRY_NAME,
                                PAGINATION_RESULT_ENTRY_NAME
                        )
                        .endControlFlow(").collect($T.toMap(r -> r.getKey(), r -> r.getValue()))", COLLECTORS.className);
            }
        } // TODO: Backwards pagination if necessary.

        return spec
                .addCode(returnCompletedFuture(currentResultName))
                .addCode(getMethodCallTail(target))
                .build();
    }

    @NotNull
    private ArrayList<String> getQueryInputs(MethodSpec.Builder spec, ObjectField referenceField) {
        var allQueryInputs = new ArrayList<String>();
        for (var input : referenceField.getNonReservedInputFields()) {
            var name = input.getName();
            spec.addParameter(inputIterableWrap(input), name);
            allQueryInputs.add(name);
        }
        if (referenceField.hasForwardPagination()) {
            spec
                    .addParameter(INTEGER.className, GraphQLReservedName.PAGINATION_FIRST.getName())
                    .addStatement(
                            "int " + PAGE_SIZE_NAME + " = $T.ofNullable($N).orElse(" + referenceField.getFirstDefault() + ")",
                            OPTIONAL.className,
                            GraphQLReservedName.PAGINATION_FIRST.getName()
                    );
            allQueryInputs.add(PAGE_SIZE_NAME);
            spec.addParameter(STRING.className, GraphQLReservedName.PAGINATION_AFTER.getName());
            allQueryInputs.add(GraphQLReservedName.PAGINATION_AFTER.getName());
        }
        return allQueryInputs;
    }

    private TypeName getReturnTypeName(ObjectField referenceField) {
        var refClassName = processedSchema.getObject(referenceField).getGraphClassName();
        var hasFPagination = referenceField.hasForwardPagination();
        TypeName returnClassName = wrapListIf(
                refClassName,
                referenceField.isIterableWrapped() || getLocalObject().isRoot() && !hasFPagination
        );
        if (hasFPagination) {
            var connectionObject = processedSchema.getConnectionObject(referenceField);
            TypeName nodeClassName = processedSchema.getObject(connectionObject.getNodeType()).getGraphClassName();
            returnClassName = ParameterizedTypeName.get(RELAY_CONNECTION.className, nodeClassName);
        }
        return returnClassName;
    }

    @NotNull
    private CodeBlock queryMethodCalls(ObjectField referenceField, TypeName returnClassName, ArrayList<String> allQueryInputs) {
        var localObject = getLocalObject();

        var queryMethodName = asQueryMethodName(referenceField.getName(), localObject.getName());
        var dbQueryCallCodeBlock = CodeBlock.builder();

        var queryLocation = localObject.getName() + FILE_NAME_SUFFIX;

        var selectionSetDeclaration = CodeBlock.of("var $L = new $T($T.getSelectionSetsFromEnvironment($N))",
                SELECTION_SET_NAME,
                referenceField.hasForwardPagination() ? CONNECTION_SELECTION_SET.className : SELECTION_SET.className,
                ENVIRONMENT_UTILS.className,
                localObject.isRoot() ? ENV_NAME : BATCHED_ENV_NAME);
        dbQueryCallCodeBlock.addStatement(selectionSetDeclaration);

        dbQueryCallCodeBlock.add("var $L = $N.$L(", QUERY_RESULT_NAME, uncapitalize(queryLocation), queryMethodName);
        dependencySet.add(new QueryDependency(queryLocation, SAVE_DIRECTORY_NAME));

        allQueryInputs.add(SELECTION_SET_NAME);

        if (!localObject.isRoot()) {
            allQueryInputs.add(0, "idSet");
        }

        if (!allQueryInputs.isEmpty()) {
            dbQueryCallCodeBlock.addStatement("$N, $L)", Dependency.CONTEXT_NAME, String.join(", ", allQueryInputs));
        }

        if (referenceField.hasRequiredPaginationFields()) {
            dbQueryCallCodeBlock.addStatement("var $L = selectionSet.contains($S) ? $N.count$L($N, $L) : null",
                    TOTAL_COUNT_NAME,
                    TOTAL_COUNT_NAME,
                    uncapitalize(queryLocation),
                    capitalize(queryMethodName),
                    Dependency.CONTEXT_NAME,
                    allQueryInputs.stream()
                            .filter(it -> !it.equals(PAGE_SIZE_NAME) && !it.equals(AFTER_NAME) && !it.equals(SELECTION_SET_NAME))
                            .collect(Collectors.joining(", "))
            );
        }

        var methodBodyBuilder = CodeBlock.builder();

        if (localObject.isRoot()) {
            methodBodyBuilder.add(dbQueryCallCodeBlock.build());
        } else {
            methodBodyBuilder
                    .beginControlFlow(
                            "$T<$T, $T> loader = $N.getDataLoaderRegistry().computeIfAbsent(\""
                                    + queryMethodName
                                    + "\""
                                    + ", name ->",
                            DATA_LOADER.className,
                            STRING.className,
                            returnClassName,
                            ENV_NAME
                    )
                    .beginControlFlow("var batchLoader = ($T<$T, $T>) (keys, " + BATCHED_ENV_NAME + ") ->",
                            MAPPED_BATCH_LOADER_WITH_CONTEXT.className, STRING.className, returnClassName)
                    .addStatement("var keyToId = keys.stream().collect($W$T.toMap(s -> s, s -> s.substring(s.lastIndexOf($S) + $L)))",
                            COLLECTORS.className, EXECUTION_STEP_PATH_ID_DELIMITER, EXECUTION_STEP_PATH_ID_DELIMITER.length())
                    .addStatement("var idSet = new $T<>(keyToId.values())", HASH_SET.className)
                    .add(dbQueryCallCodeBlock.build())
                    .addStatement(
                            "var "
                                    + MAP_RESULT_NAME
                                    + " = keyToId.entrySet().stream()$W"
                                    + ".filter(it -> $N.get(it.getValue()) != null)$W"
                                    + ".collect($T.toMap($T.Entry::getKey, it -> $N.get(it.getValue())))",
                            QUERY_RESULT_NAME,
                            COLLECTORS.className,
                            MAP.className,
                            QUERY_RESULT_NAME
                    );
        }
        return methodBodyBuilder.build();
    }

    @NotNull
    private CodeBlock getMethodCallTail(ObjectField referenceField) {
        var localObject = getLocalObject();
        var closeMethodCall = CodeBlock.builder();
        if (!localObject.isRoot()) {
            closeMethodCall
                    .endControlFlow("")
                    .addStatement("return $T.newMappedDataLoader(batchLoader)", DATA_LOADER_FACTORY.className)
                    .endControlFlow(")")
                    .add(
                            "return loader.load($N.getExecutionStepInfo().getPath().toString() + $S + $N.getId(), $N)",
                            ENV_NAME,
                            EXECUTION_STEP_PATH_ID_DELIMITER,
                            uncapitalize(localObject.getName()),
                            ENV_NAME
                    );

            if (referenceField.getFieldType().isIterableNonNullable()) {
                closeMethodCall.addStatement(".thenApply(data -> $T.ofNullable(data).orElse($T.of()))", OPTIONAL.className, LIST.className);
            } else {
                closeMethodCall.add(";");
            }
        }
        return closeMethodCall.build();
    }

    /**
     * Template-style code for the large pagination section.
     */
    private String getPaginationSegmentString() {
        return "var size = $math:T.min($dbResult:N.size(), $pageSize:N);\n" +
                "var items = $dbResult:N.subList(0, size);\n" +
                "var firstItem = items.size() == 0 ? null : new $connectionCursorClass:T(items.get(0).getId());\n" +
                "var lastItem = items.size() == 0 ? null : new $connectionCursorClass:T(items.get(items.size() - 1).getId());\n" +
                "var $resultName:L = $connectionClass:T\n" +
                "        .<$nodeType:N>builder()\n" +
                "        .setPageInfo(\n" +
                "                new $pageInfoClass:T(firstItem, lastItem, false, $dbResult:N.size() > $pageSize:N)\n" +
                "        )\n" +
                "        .setNodes(items)\n" +
                "        .setEdges(\n" +
                "                items\n" +
                "                        .stream()\n" +
                "                        .map(item -> new $edgeClass:T<$nodeType:N>(item, new $connectionCursorClass:T(item.getId())))\n" +
                "                        .collect($collectors:T.toList())\n" +
                "        )\n" +
                "        .setTotalCount($totalCount:N != null ? $math:T.min($maxNoOfNodes:L, $totalCount:N) : null)\n" +
                "        .build();\n";
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getReferredFieldsFromObjectNames(processedSchema.getNamesWithTableOrConnections())
                .stream()
                .filter(ObjectField::isGenerated)
                .map(this::generate)
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject().getReferredFieldsFromObjectNames(processedSchema.getNamesWithTableOrConnections()).stream();
        return getLocalObject().isRoot()
                ? fieldStream.allMatch(ObjectField::isGenerated)
                : fieldStream.allMatch(f -> !f.isResolver() || f.isGenerated());
    }
}
