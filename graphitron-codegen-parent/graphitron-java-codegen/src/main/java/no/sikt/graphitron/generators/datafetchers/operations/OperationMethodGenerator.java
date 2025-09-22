package no.sikt.graphitron.generators.datafetchers.operations;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFetcher;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFuture;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

/**
 * This class generates the data fetchers for default fetch or mutation queries with potential arguments or pagination.
 */
public class OperationMethodGenerator extends DataFetcherMethodGenerator {
    public OperationMethodGenerator(ObjectField source, ProcessedSchema processedSchema) {
        super(source, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var parser = new InputParser(target, processedSchema);
        var methodCall = getMethodCall(target, parser, false); // Note, do this before declaring services.
        var mutationMethodCall = getSourceContainer().getName().equals(SCHEMA_MUTATION.getName()) ? getMethodCall(target, parser, true) : CodeBlock.empty();
        dataFetcherWiring.add(new WiringContainer(target.getName(), getSourceContainer().getName(), target.getName()));
        return getDefaultSpecBuilder(target.getName(), wrapFetcher(wrapFuture(getReturnTypeName(target))))
                .beginControlFlow("return $N ->", VARIABLE_ENV)
                .addCode(extractParams(target))
                .addCode(declareContextArgs(target))
                .addCode(transformInputs(target, parser))
                .addCode(declareAllServiceClasses(target.getName()))
                .addCode(mutationMethodCall)
                .addCode(methodCall)
                .endControlFlow("") // Keep this, logic to set semicolon only kicks in if a string is set.
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

        if (target.hasServiceReference() && !source.getName().equals(SCHEMA_MUTATION.getName())) {
            return !LookupHelpers.lookupExists((ObjectField) target, processedSchema);
        }

        return true;
    }

    @Override
    public List<MethodSpec> generateAll() {
        var localObject = getSourceContainer();
        if (localObject == null || localObject.isExplicitlyNotGenerated()) {
            return List.of();
        }
        var source = getSource();
        if (!generationCondition(source)) {
            return List.of();
        }

        var generated = generate(source);
        if (generated.code().isEmpty()) {
            return List.of();
        }
        return List.of(generated);
    }
}
