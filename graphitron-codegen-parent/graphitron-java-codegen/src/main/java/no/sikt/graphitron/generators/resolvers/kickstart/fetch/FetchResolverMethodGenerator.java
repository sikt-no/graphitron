package no.sikt.graphitron.generators.resolvers.kickstart.fetch;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.KickstartResolverMethodGenerator;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declarePageSize;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.sikt.graphitron.mappings.JavaPoetClassName.DATA_FETCHING_ENVIRONMENT;
import static no.sikt.graphql.naming.GraphQLReservedName.*;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for default fetch queries with potential arguments or pagination.
 */
public class FetchResolverMethodGenerator extends KickstartResolverMethodGenerator {
    public FetchResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        if (!generationCondition(target)) {
            return MethodSpec.methodBuilder(target.getName()).build();
        }
        var spec = getSpecWithParams(target).addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV);
        // _service temporary special case. Omitting this will cause compilation errors.
        if (processedSchema.isFederationService(target)) {
            return spec.addCode(returnWrap(CodeBlock.of("null"))).build();
        }
        // _entities temporary special case. Omitting this will cause compilation errors.
        if (target.getName().equals(FEDERATION_ENTITIES_FIELD.getName())) {
            return spec.addCode(returnWrap(CodeBlock.of("null"))).build();
        }

        var parser = new InputParser(target, processedSchema);
        var methodCall = getMethodCall(target, parser, false); // Note, do this before declaring services.
        return spec
                .addCode(transformInputs(target, parser))
                .addCode(declareAllServiceClasses(target.getName()))
                .addCode(methodCall)
                .build();
    }

    protected boolean generationCondition(GenerationField target) {
        if (processedSchema.isInterface(target) && target.getTypeName().equals(NODE_TYPE.getName())) {
            return false;
        }

        if (target.hasServiceReference()) {
            return !LookupHelpers.lookupExists((ObjectField) target, processedSchema);
        }

        return true;
    }

    private MethodSpec.Builder getSpecWithParams(ObjectField target) {
        var spec = getDefaultSpecBuilder(target.getName(), getReturnTypeName(target));

        var localObject = getLocalObject();
        if (!localObject.isOperationRoot()) {
            spec.addParameter(localObject.getGraphClassName(), uncapitalize(localObject.getName()));
        }

        target.getArguments().forEach(input -> spec.addParameter(iterableWrapType(input), uncapitalize(input.getName())));
        if (target.hasForwardPagination()) {
            spec.addCode(declarePageSize(target.getFirstDefault()));
        }
        return spec;
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(this::generationCondition)
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .toList();
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject()
                .getFields()
                .stream()
                .filter(this::generationCondition);
        return getLocalObject().isOperationRoot()
                ? fieldStream.allMatch(GenerationField::isGeneratedWithResolver)
                : fieldStream.allMatch(f -> (!f.isResolver() || f.isGeneratedWithResolver()));
    }
}
