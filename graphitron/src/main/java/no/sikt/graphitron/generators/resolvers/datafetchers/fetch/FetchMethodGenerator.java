package no.sikt.graphitron.generators.resolvers.datafetchers.fetch;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declareArgs;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFetcher;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFuture;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

/**
 * This class generates the fetchers for default fetch queries with potential arguments or pagination.
 */
public class FetchMethodGenerator extends DataFetcherMethodGenerator {
    public FetchMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        if (!generationCondition(target)) {
            return MethodSpec.methodBuilder(target.getName()).build();
        }
        var spec = getDefaultSpecBuilder(target.getName(), wrapFetcher(wrapFuture(getReturnTypeName(target))))
                .beginControlFlow("return $N ->", VARIABLE_ENV);
        // _service temporary special case. Omitting this will make the schema complain that the data fetcher for this field is missing.
        if (processedSchema.isFederationService(target)) {
            return spec
                    .addCode(returnWrap(CodeBlock.of("null")))
                    .endControlFlow("")
                    .build();
        }

        var parser = new InputParser(target, processedSchema);
        var methodCall = queryMethodCall(target, parser); // Note, do this before declaring services.
        dataFetcherWiring.add(new WiringContainer(target.getName(), getLocalObject().getName(), target.getName()));
        return spec
                .addCode(declareArgs(target))
                .addCode(extractParams(target))
                .addCode(transformInputs(target, parser))
                .addCode(declareAllServiceClasses(target.getName()))
                .addCode(methodCall)
                .endControlFlow("") // Keep this, logic to set semicolon only kicks in if a string is set.
                .build();
    }

    protected boolean generationCondition(GenerationField target) {
        if (processedSchema.isInterface(target) && target.getTypeName().equals(NODE_TYPE.getName()) || target.getName().equals(FEDERATION_ENTITIES_FIELD.getName())) {
            return false;
        }

        if (target.hasServiceReference()) {
            return !LookupHelpers.lookupExists((ObjectField) target, processedSchema);
        }

        return true;
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
}
