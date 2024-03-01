package no.fellesstudentsystem.graphitron.generators.resolvers.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.fields.OrderByEnumField;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphitron.generators.dependencies.QueryDependency;
import no.fellesstudentsystem.graphql.helpers.queries.LookupHelpers;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapListIf;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator.SAVE_DIRECTORY_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.PAGINATION_AFTER;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for default fetch queries with potential arguments or pagination.
 */
public class FetchResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
    private static final String QUERY_RESULT_NAME = "dbResult", LOOKUP_KEYS_NAME = "keys", TYPE_NAME = "type";

    public FetchResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var returnClassName = getReturnTypeName(target);
        var spec = getDefaultSpecBuilder(target.getName(), returnClassName);

        var localObject = getLocalObject();
        if (!localObject.isRoot()) {
            spec.addParameter(localObject.getGraphClassName(), uncapitalize(localObject.getName()));
        }

        var allQueryInputs = getQueryInputs(spec, target);
        return spec
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
                .addCode(queryMethodCalls(target, returnClassName, allQueryInputs))
                .addCode(getMethodCallTail(target))
                .build();
    }

    @NotNull
    private ArrayList<String> getQueryInputs(MethodSpec.Builder spec, ObjectField referenceField) {
        var allQueryInputs = new ArrayList<String>();

        referenceField
                .getNonReservedArgumentsWithOrderField()
                .forEach(it -> {
                    var name = it.getName();
                    spec.addParameter(inputIterableWrap(it), name);
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

    private TypeName getReturnTypeName(ObjectField referenceField) {
        var refClassName = processedSchema.getObject(referenceField).getGraphClassName();
        var hasFPagination = referenceField.hasForwardPagination();
        TypeName returnClassName = wrapListIf(
                refClassName,
                referenceField.isIterableWrapped() || getLocalObject().isRoot() && !hasFPagination
        );
        if (hasFPagination) {
            TypeName nodeClassName = processedSchema.getObjectOrConnectionNode(referenceField).getGraphClassName();
            returnClassName = ParameterizedTypeName.get(RELAY_CONNECTION.className, nodeClassName);
        }
        return returnClassName;
    }

    @NotNull
    private CodeBlock queryMethodCalls(ObjectField referenceField, TypeName returnClassName, ArrayList<String> allQueryInputs) {
        var localObject = getLocalObject();
        var queryLocation = asQueryClass(localObject.getName());
        dependencySet.add(new QueryDependency(queryLocation, SAVE_DIRECTORY_NAME));
        var isRoot = localObject.isRoot();
        var hasLookup = LookupHelpers.lookupExists(referenceField, processedSchema);
        var hasPagination = referenceField.hasRequiredPaginationFields();

        var inputString = String.join(", ", allQueryInputs);
        if (isRoot && !hasPagination && !hasLookup) {
            return getSimpleRootDBCall(referenceField.getName(), queryLocation, inputString);
        }

        var queryMethodName = asQueryMethodName(referenceField.getName(), localObject.getName());
        var dataBlock = CodeBlock.builder();
        if (hasLookup) { // Assume all keys are correlated.
            dataBlock
                    .add(declare(LOOKUP_KEYS_NAME, LookupHelpers.getLookupKeysAsList(referenceField, processedSchema)))
                    .add("return $T.loadDataAsLookup($N, $N, ", DATA_LOADERS.className, VARIABLE_ENV, LOOKUP_KEYS_NAME);
        } else if (!isRoot) {
            dataBlock.add(
                    "$T<$T, $T> $L = $T.getDataLoader($N, $S, ",
                    DATA_LOADER.className,
                    STRING.className,
                    returnClassName,
                    LOADER_NAME,
                    DATA_LOADERS.className,
                    VARIABLE_ENV,
                    queryMethodName
            );
        } else {
            dataBlock.add("return $T.loadData($N, ", DATA_LOADERS.className, VARIABLE_ENV);
        }

        var queryFunction = queryDBFunction(queryLocation, queryMethodName, inputString, !isRoot || hasLookup, !isRoot && !hasLookup);
        if (hasPagination && !hasLookup) {
            var filteredInputs = allQueryInputs
                    .stream()
                    .filter(it -> !it.equals(PAGE_SIZE_NAME) && !it.equals(PAGINATION_AFTER.getName()) && !it.equals(SELECTION_SET_NAME) &&
                            referenceField.getOrderField().map(AbstractField::getName).map(orderByField -> !orderByField.equals(it)).orElse(true))
                    .collect(Collectors.joining(", "));
            var inputsWithId = isRoot ? filteredInputs : (filteredInputs.isEmpty() ? IDS_NAME : IDS_NAME + ", " + filteredInputs);
            var countFunction = countDBFunction(queryLocation, queryMethodName, inputsWithId);
            return dataBlock.addStatement("$N, $L,\n$L,\n$L,\n$L)", PAGE_SIZE_NAME, GeneratorConfig.getMaxAllowedPageSize(), queryFunction, countFunction, getIDFunction(referenceField)).build();
        }

        return dataBlock.addStatement("$L)", queryFunction).build();
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
                            .add("$T.<$T, $T<$T, $T>>of(\n",
                                    MAP.className, STRING.className, FUNCTION.className, objectNode.getGraphClassName(), STRING.className)
                            .indent()
                            .add("$L\n", orderByFieldMapEntries)
                            .unindent()
                            .add(").get($L.get$L().toString()).apply(it)", orderInputField.getName(), capitalize(GraphQLReservedName.ORDER_BY_FIELD.getName()))
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
    private CodeBlock getSimpleRootDBCall(String referenceFieldName, String queryLocation, String inputString) {
        return CodeBlock
                .builder()
                .add(declare(SELECTION_SET_NAME, getHelperSelectionSet()))
                .add(
                        declare(
                                QUERY_RESULT_NAME,
                                CodeBlock.of(
                                        "$N.$L($N$L, $N)",
                                        uncapitalize(queryLocation),
                                        asQueryMethodName(referenceFieldName, getLocalObject().getName()),
                                        VariableNames.CONTEXT_NAME,
                                        inputString.isEmpty() ? empty() : CodeBlock.of(", $L", inputString),
                                        SELECTION_SET_NAME
                                )
                        )
                )
                .add(returnCompletedFuture(QUERY_RESULT_NAME))
                .build();
    }

    @NotNull
    private CodeBlock getMethodCallTail(ObjectField referenceField) {
        var localObject = getLocalObject();
        if (localObject.isRoot()) {
            return empty();
        }

        return CodeBlock.builder().addStatement(
                "return $T.$L($N, $N.getId(), $N)",
                DATA_LOADERS.className,
                referenceField.isIterableWrapped() && referenceField.isNonNullable() ? "loadNonNullable" : "load",
                LOADER_NAME,
                uncapitalize(localObject.getName()),
                VARIABLE_ENV
        ).build();
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
