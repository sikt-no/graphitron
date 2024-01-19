package no.fellesstudentsystem.graphitron.generators.resolvers;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
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
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator.FILE_NAME_SUFFIX;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.wrapListIf;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asQueryMethodName;
import static no.fellesstudentsystem.graphitron.generators.db.FetchDBClassGenerator.SAVE_DIRECTORY_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.PAGINATION_AFTER;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for default fetch queries with potential arguments or pagination.
 */
public class FetchResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
    private static final String QUERY_RESULT_NAME = "dbResult";

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
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, ENV_NAME)
                .addCode(queryMethodCalls(target, returnClassName, allQueryInputs))
                .addCode(getMethodCallTail(target))
                .build();
    }

    @NotNull
    private ArrayList<String> getQueryInputs(MethodSpec.Builder spec, ObjectField referenceField) {
        var allQueryInputs = new ArrayList<String>();
        for (var input : referenceField.getNonReservedArguments()) {
            var name = input.getName();
            spec.addParameter(inputIterableWrap(input), name);
            allQueryInputs.add(name);
        }
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
        var queryLocation = localObject.getName() + FILE_NAME_SUFFIX;
        dependencySet.add(new QueryDependency(queryLocation, SAVE_DIRECTORY_NAME));
        var isRoot = localObject.isRoot();
        var hasLookup = referenceField.hasLookupKey();
        var hasPagination = referenceField.hasRequiredPaginationFields();

        var inputString = String.join(", ", allQueryInputs);
        if (isRoot && !hasPagination && !hasLookup) {
            return getSimpleRootDBCall(referenceField.getName(), queryLocation, inputString);
        }

        var queryMethodName = asQueryMethodName(referenceField.getName(), localObject.getName());
        var dataBlock = CodeBlock.builder();
        if (hasLookup) { // Key must be an argument. Allowing input types with it would require recursion.
            dataBlock.add(
                    "return $T.loadDataAsLookup($N, $N, ",
                    DATA_LOADERS.className,
                    ENV_NAME,
                    referenceField.getLookupKey().getName()
            );
        } else if (!isRoot) {
            dataBlock.add(
                    "$T<$T, $T> $L = $T.getDataLoader($N, $S, ",
                    DATA_LOADER.className,
                    STRING.className,
                    returnClassName,
                    LOADER_NAME,
                    DATA_LOADERS.className,
                    ENV_NAME,
                    queryMethodName
            );
        } else {
            dataBlock.add("return $T.loadData($N, ", DATA_LOADERS.className, ENV_NAME);
        }

        var queryFunction = queryDBFunction(queryLocation, queryMethodName, inputString, !isRoot || hasLookup, !isRoot && !hasLookup);
        if (hasPagination && !hasLookup) {
            var filteredInputs = allQueryInputs
                    .stream()
                    .filter(it -> !it.equals(PAGE_SIZE_NAME) && !it.equals(PAGINATION_AFTER.getName()) && !it.equals(SELECTION_SET_NAME))
                    .collect(Collectors.joining(", "));
            var inputsWithId = isRoot ? filteredInputs : (filteredInputs.isEmpty() ? IDS_NAME : IDS_NAME + ", " + filteredInputs);
            var countFunction = countDBFunction(queryLocation, queryMethodName, inputsWithId);
            return dataBlock.addStatement("$N, $L,\n$L,\n$L,\n$L)", PAGE_SIZE_NAME, GeneratorConfig.getMaxAllowedPageSize(), queryFunction, countFunction, getIDFunction()).build();
        }

        return dataBlock.addStatement("$L)", queryFunction).build();
    }

    @NotNull
    private CodeBlock getSimpleRootDBCall(String referenceFieldName, String queryLocation, String inputString) {
        var queryBlock = CodeBlock.builder().addStatement("var $L = $T.getSelectionSet($N)", SELECTION_SET_NAME, RESOLVER_HELPERS.className, ENV_NAME);
        queryBlock.add("var $L = $N.$L($N", QUERY_RESULT_NAME, uncapitalize(queryLocation), asQueryMethodName(referenceFieldName, getLocalObject().getName()), Dependency.CONTEXT_NAME);
        if (!inputString.isEmpty()) {
            queryBlock.add(", $L", inputString);
        }
        return queryBlock
                .addStatement(", $N)", SELECTION_SET_NAME)
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
                ENV_NAME
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
