package no.fellesstudentsystem.graphitron.generators.resolvers.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
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
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ServiceCodeBlocks.callQueryBlock;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator.SAVE_DIRECTORY_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.PAGINATION_AFTER;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for default fetch queries with potential arguments or pagination.
 */
public class FetchResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
    private static final String LOOKUP_KEYS_NAME = "keys";

    public FetchResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var spec = getDefaultSpecBuilder(target.getName(), getReturnTypeName(target));

        var localObject = getLocalObject();
        if (!localObject.isOperationRoot()) {
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
        var isRoot = localObject.isOperationRoot();
        var hasLookup = LookupHelpers.lookupExists(target, processedSchema);

        var inputString = String.join(", ", allQueryInputs);
        var queryMethodName = asQueryMethodName(target.getName(), localObject.getName());
        var queryFunction = queryFunction(queryLocation, queryMethodName, inputString, !isRoot || hasLookup, !isRoot && !hasLookup, false);
        if (hasLookup) { // Assume all keys are correlated.
            return CodeBlock
                    .builder()
                    .add(declare(LOOKUP_KEYS_NAME, LookupHelpers.getLookupKeysAsList(target, processedSchema)))
                    .addStatement("return $L.$L($N, $L)", newDataFetcher(), "loadLookup", LOOKUP_KEYS_NAME, queryFunction)
                    .build();
        }
        return callQueryBlock(target, queryLocation, queryMethodName, allQueryInputs, localObject, queryFunction, empty(), false, processedSchema);
    }

    @Override
    public List<MethodSpec> generateAll() {
        return ((ObjectDefinition) getLocalObject())
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it))
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .map(this::generate)
                .filter(it -> !it.code.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it))
                .filter(it -> !it.hasServiceReference());
        return getLocalObject().isOperationRoot()
                ? fieldStream.allMatch(GenerationField::isGeneratedWithResolver)
                : fieldStream.allMatch(f -> (!f.isResolver() || f.isGeneratedWithResolver()));
    }
}
