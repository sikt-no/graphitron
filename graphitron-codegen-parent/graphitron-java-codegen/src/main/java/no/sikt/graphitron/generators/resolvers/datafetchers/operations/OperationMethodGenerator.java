package no.sikt.graphitron.generators.resolvers.datafetchers.operations;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.codebuilding.MappingCodeBlocks;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asListedNameIf;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asResultName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFetcher;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFuture;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

/**
 * This class generates the data fetchers for default fetch or mutation queries with potential arguments or pagination.
 */
public class OperationMethodGenerator extends DataFetcherMethodGenerator {
    private final boolean isMutation;

    public OperationMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
        isMutation = localObject.getName().equals(SCHEMA_MUTATION.getName());
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var parser = new InputParser(target, processedSchema);
        var methodCall = getMethodCall(target, parser, isMutation); // Note, do this before declaring services.
        dataFetcherWiring.add(new WiringContainer(target.getName(), getLocalObject().getName(), target.getName()));
        var returnType = isMutation ? iterableWrapType(target) : getReturnTypeName(target);
        return getDefaultSpecBuilder(target.getName(), wrapFetcher(wrapFuture(returnType)))
                .beginControlFlow("return $N ->", VARIABLE_ENV)
                .addCode(declareArgs(target))
                .addCode(extractParams(target))
                .addCode(declareContextArgs(target))
                .addCode(transformInputs(target, parser))
                .addCode(declareAllServiceClasses(target.getName()))
                .addCode(methodCall)
                .addCode(isMutation ? generateSchemaOutputs(target, parser) : CodeBlock.empty())
                .endControlFlow("") // Keep this, logic to set semicolon only kicks in if a string is set.
                .build();
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    protected CodeBlock generateSchemaOutputs(ObjectField target, InputParser parser) {
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return CodeBlock.empty();
        }

        var mapperContext = MapperContext.createResolverContext(target, false, processedSchema);
        var resolverResultName = processedSchema.isObject(target)
                ? asListedNameIf(target.getTypeName(), target.isIterableWrapped())
                : asResultName(target.getUnprocessedFieldOverrideInput());
        var returnValue = processedSchema.isObject(target) || target.hasServiceReference()
                ? CodeBlock.of(resolverResultName)
                : getIDMappingCode(mapperContext, target, processedSchema, parser);
        var outputBlock = target.hasServiceReference()
                ? MappingCodeBlocks.generateSchemaOutputs(mapperContext, processedSchema)
                : makeResponses(mapperContext, target, processedSchema, parser);
        return CodeBlock
                .builder()
                .add(outputBlock)
                .add(returnCompletedFuture(returnValue))
                .build();
    }

    protected boolean generationCondition(GenerationField target) {
        if (!target.isGeneratedWithResolver()) {
            return false;
        }

        if (target.getName().equals(FEDERATION_ENTITIES_FIELD.getName())) {
            return false;
        }

        if (processedSchema.isFederationService(target)) {
            return false;
        }

        if (processedSchema.isInterface(target) && target.getTypeName().equals(NODE_TYPE.getName())) {
            return false;
        }

        if (target.hasServiceReference() && !isMutation) {
            return !LookupHelpers.lookupExists((ObjectField) target, processedSchema);
        }

        return true;
    }

    @Override
    public List<MethodSpec> generateAll() {
        var localObject = getLocalObject();
        if (localObject == null || localObject.isExplicitlyNotGenerated()) {
            return List.of();
        }

        return localObject
                .getFields()
                .stream()
                .filter(this::generationCondition)
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .toList();
    }
}
