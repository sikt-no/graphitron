package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.definitions.fields.ArgumentField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.NODE_ID_STRATEGY;
import static no.sikt.graphitron.mappings.JavaPoetClassName.RESOLVER_HELPERS;

abstract public class DataFetcherMethodGenerator extends ResolverMethodGenerator {
    protected final List<WiringContainer> dataFetcherWiring = new ArrayList<>();

    public DataFetcherMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return super
                .getDefaultSpecBuilder(methodName, returnType)
                .addModifiers(Modifier.STATIC)
                .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, NODE_ID_STRATEGY_NAME);
    }

    protected CodeBlock extractParams(ObjectField target) {
        var localObject = getLocalObject();
        return CodeBlock
                .builder()
                .declareIf(
                        !localObject.isOperationRoot(),
                        localObject.getName(),
                        () -> asCast(localObject.getGraphClassName(), asMethodCall(VARIABLE_ENV, METHOD_SOURCE_NAME))
                )
                .addAll(target.getArguments().stream().map(this::declareArgument).toList())
                .addIf(target.hasForwardPagination(), declarePageSize(target.getFirstDefault()))
                .build();
    }

    private CodeBlock declareArgument(ArgumentField field) {
        var getBlock = CodeBlock.of("$N.get($S)", VARIABLE_ARGS, field.getName());
        var transformBlock = !processedSchema.isRecordType(field) ? asCast(iterableWrapType(field), getBlock) : transformDTOBlock(field, getBlock);
        return CodeBlock.declare(field.getName(), transformBlock);
    }

    protected CodeBlock transformDTOBlock(GenerationField field, CodeBlock source) {
        return CodeBlock.of(
                "$T.$L($L, $T.class)",
                RESOLVER_HELPERS.className,
                field.isIterableWrapped() ? "transformDTOList" : "transformDTO",
                source,
                processedSchema.getRecordType(field).getGraphClassName()
        );
    }

    @Override
    public List<WiringContainer> getDataFetcherWiring() {
        return dataFetcherWiring;
    }
}
