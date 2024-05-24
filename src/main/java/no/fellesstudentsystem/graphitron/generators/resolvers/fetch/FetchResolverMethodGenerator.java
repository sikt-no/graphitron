package no.fellesstudentsystem.graphitron.generators.resolvers.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.fields.OrderByEnumField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
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
    private static final String LOOKUP_KEYS_NAME = "keys", TYPE_NAME = "type";

    public FetchResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var spec = getDefaultSpecBuilder(target.getName(), getReturnTypeName(target));

        var localObject = getLocalObject();
        if (!localObject.isRoot()) {
            spec.addParameter(localObject.getGraphClassName(), uncapitalize(localObject.getName()));
        }

        var allQueryInputs = getQueryInputs(spec, target);
        return spec
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
                .addCode(queryMethodCalls(target, allQueryInputs))
                .build();
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
    private CodeBlock queryMethodCalls(ObjectField target, ArrayList<String> allQueryInputs) {
        var localObject = getLocalObject();
        var queryLocation = asQueryClass(localObject.getName());
        dependencySet.add(new QueryDependency(queryLocation, SAVE_DIRECTORY_NAME));
        var isRoot = localObject.isRoot();
        var hasLookup = LookupHelpers.lookupExists(target, processedSchema);
        var hasPagination = target.hasRequiredPaginationFields();

        var inputString = String.join(", ", allQueryInputs);
        if (isRoot && !hasPagination && !hasLookup) {
            return getSimpleRootDBCall(target.getName(), queryLocation, inputString);
        }

        var queryMethodName = asQueryMethodName(target.getName(), localObject.getName());
        var queryFunction = queryDBFunction(queryLocation, queryMethodName, inputString, !isRoot || hasLookup, !isRoot && !hasLookup, true);

        if (hasLookup) { // Assume all keys are correlated.
            return CodeBlock
                    .builder()
                    .add(declare(LOOKUP_KEYS_NAME, LookupHelpers.getLookupKeysAsList(target, processedSchema)))
                    .addStatement("return $L.$L($N, $L)", newDataFetcher(), "loadLookup", LOOKUP_KEYS_NAME, queryFunction)
                    .build();
        }

        var dataBlock = CodeBlock.builder();
        if (!isRoot) {
            dataBlock
                    .add("return $L.$L(\n", newDataFetcher(), target.isIterableWrapped() && target.isNonNullable() ? "loadNonNullable" : "load")
                    .indent()
                    .indent()
                    .add("$S, $N.getId(), ", queryMethodName, uncapitalize(localObject.getName())
            );
        } else {
            dataBlock.add("return $L.$L(\n", newDataFetcher(), "load").indent().indent();
        }

        if (!hasPagination) {
            return dataBlock
                    .add("$L\n", queryFunction)
                    .unindent()
                    .unindent()
                    .addStatement(")", queryFunction)
                    .build();
        }

        var filteredInputs = allQueryInputs
                .stream()
                .filter(it -> !it.equals(PAGE_SIZE_NAME) && !it.equals(PAGINATION_AFTER.getName()) && !it.equals(SELECTION_SET_NAME) &&
                        target.getOrderField().map(AbstractField::getName).map(orderByField -> !orderByField.equals(it)).orElse(true))
                .collect(Collectors.joining(", "));
        var inputsWithId = isRoot ? filteredInputs : (filteredInputs.isEmpty() ? IDS_NAME : IDS_NAME + ", " + filteredInputs);
        var countFunction = countDBFunction(queryLocation, queryMethodName, inputsWithId);
        return dataBlock
                .add("$N, $L,\n$L,\n$L,\n$L\n", PAGE_SIZE_NAME, GeneratorConfig.getMaxAllowedPageSize(), queryFunction, countFunction, getIDFunction(target))
                .unindent()
                .unindent()
                .addStatement(")")
                .build();
    }

    /**
     * @return CodeBlock consisting of a function for a getId call.
     */
    @NotNull
    protected CodeBlock getIDFunction(ObjectField referenceField) {
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
    private CodeBlock getSimpleRootDBCall(String referenceFieldName, String queryLocation, String inputString) {
        return CodeBlock
                .builder()
                .add(declareContextVariable())
                .add(declare(SELECTION_SET_NAME, getHelperSelectionSet()))
                .add(
                        returnCompletedFuture(
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
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return ((ObjectDefinition) getLocalObject())
                .getFieldsReferringTo(processedSchema.getNamesWithTableOrConnections())
                .stream()
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .map(this::generate)
                .filter(it -> !it.code.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject()
                .getFieldsReferringTo(processedSchema.getNamesWithTableOrConnections())
                .stream()
                .filter(it -> !it.hasServiceReference());
        return getLocalObject().isRoot()
                ? fieldStream.allMatch(GenerationField::isGeneratedWithResolver)
                : fieldStream.allMatch(f -> (!f.isResolver() || f.isGeneratedWithResolver()));
    }
}
