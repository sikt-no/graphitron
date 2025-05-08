package no.sikt.graphitron.generators.abstractions;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.configuration.GeneratorConfig;
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
import static org.apache.commons.lang3.StringUtils.uncapitalize;

abstract public class DataFetcherMethodGenerator extends ResolverMethodGenerator {
    protected final List<WiringContainer> dataFetcherWiring = new ArrayList<>();

    public DataFetcherMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        MethodSpec.Builder builder = super
                .getDefaultSpecBuilder(methodName, returnType)
                .addModifiers(Modifier.STATIC);
        if (GeneratorConfig.shouldMakeNodeStrategy()) {
            builder.addParameter(NODE_ID_STRATEGY.className, NODE_ID_STRATEGY_NAME);
        }

        return builder;
    }

    protected CodeBlock extractParams(ObjectField target) {
        var code = CodeBlock.builder();

        var localObject = getLocalObject();
        if (!localObject.isOperationRoot()) {
            var getSource = asCast(localObject.getGraphClassName(), asMethodCall(VARIABLE_ENV, METHOD_SOURCE_NAME));
            code.add(declare(uncapitalize(localObject.getName()), getSource));
        }

        target.getArguments().forEach(it -> code.add(declareArgument(it)));
        if (target.hasForwardPagination()) {
            code.add(declarePageSize(target.getFirstDefault()));
        }
        return code.build();
    }

    private CodeBlock declareArgument(ArgumentField field) {
        var getBlock = CodeBlock.of("$N.get($S)", VARIABLE_ARGS, field.getName());
        var transformBlock = !processedSchema.isRecordType(field) ? asCast(iterableWrapType(field), getBlock) : transformDTOBlock(field, getBlock);
        return declare(field.getName(), transformBlock);
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
