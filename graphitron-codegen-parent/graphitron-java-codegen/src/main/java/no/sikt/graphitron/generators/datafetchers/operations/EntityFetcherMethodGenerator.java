package no.sikt.graphitron.generators.datafetchers.operations;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asEntityQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.ENVIRONMENT_HANDLER;
import static no.sikt.graphitron.mappings.JavaPoetClassName.STRING;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

/**
 * This class generates the main entity resolver.
 */
public class EntityFetcherMethodGenerator extends DataFetcherMethodGenerator {
    public static final String METHOD_NAME = "entityFetcher";

    public EntityFetcherMethodGenerator(ProcessedSchema processedSchema) {
        super(null, processedSchema);
        if (processedSchema.hasEntitiesField()) {
            dataFetcherWiring.add(new WiringContainer(METHOD_NAME, processedSchema.getQueryType().getName(), FEDERATION_ENTITIES_FIELD.getName()));
        }
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var cases = CodeBlock.builder();
        var entities = processedSchema.getEntities().values();
        for (var entity : entities) {
            var params = new ArrayList<String>();
            params.add(VAR_CONTEXT);
            if (GeneratorConfig.shouldMakeNodeStrategy()) params.add(VAR_NODE_STRATEGY);
            params.add(VAR_ITERATOR);
            if (GeneratorConfig.optionalSelectIsEnabled()) params.add(VAR_SELECT);

            var fetchBlock = CodeBlock.of(
                    "$T.$L($L)",
                    getQueryClassName(asQueryClass(entity.getName())),
                    asEntityQueryMethodName(entity.getName()),
                    params.stream().map(CodeBlock::of).collect(CodeBlock.joining(", ")));
            cases
                    .add("case $S: ", entity.getName())
                    .add(returnWrap(CodeBlock.of("($T) $L",
                                    processedSchema.getUnion(target.getTypeName()).getGraphClassName(),
                                    transformDTOBlock(new VirtualSourceField(entity, target.getTypeName()), fetchBlock))
                            )
                    );
        }

        return getDefaultSpecBuilder(METHOD_NAME, wrapFetcher(wrapList(processedSchema.getUnion(target.getTypeName()).getGraphClassName())))
                .beginControlFlow(
                        "return $N -> (($T) $N.getArgument($S)).stream().map($L ->",
                        VAR_ENV,
                        wrapList(getObjectMapTypeName()),
                        VAR_ENV,
                        FEDERATION_REPRESENTATIONS_ARGUMENT.getName(),
                        VAR_ITERATOR
                )
                .declare(VAR_ENV_HELPER, "new $T($N)", ENVIRONMENT_HANDLER.className, VAR_ENV)
                .declare(VAR_CONTEXT, "$N$L", VAR_ENV_HELPER, asMethodCall(METHOD_CONTEXT_NAME))
                .declareIf(GeneratorConfig.optionalSelectIsEnabled(), VAR_SELECT, "$N.getSelect()", VAR_ENV_HELPER)
                .beginControlFlow("switch (($T) $N.get($S))", STRING.className, VAR_ITERATOR, TYPE_NAME.getName())
                .addCode(cases.build())
                .addCode("default: $L", returnWrap("null"))
                .endControlFlow()
                .endControlFlow(")$L", collectToList())
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (!processedSchema.hasEntitiesField()) {
            return List.of();
        }
        return List.of(generate(processedSchema.getEntitiesField()));
    }
}
