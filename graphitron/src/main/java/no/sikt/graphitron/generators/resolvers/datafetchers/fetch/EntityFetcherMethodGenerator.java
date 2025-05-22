package no.sikt.graphitron.generators.resolvers.datafetchers.fetch;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

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
            var fetchBlock = GeneratorConfig.shouldMakeNodeStrategy() ?
                    CodeBlock.of(
                            "$T.$L($N, $N, $N)",
                            getQueryClassName(asQueryClass(entity.getName())),
                            asEntityQueryMethodName(entity.getName()),
                            CONTEXT_NAME,
                            NODE_ID_STRATEGY_NAME,
                            VARIABLE_INTERNAL_ITERATION)
                    : CodeBlock.of(
                    "$T.$L($N, $N)",
                    getQueryClassName(asQueryClass(entity.getName())),
                    asEntityQueryMethodName(entity.getName()),
                    CONTEXT_NAME,
                    VARIABLE_INTERNAL_ITERATION);
            cases
                    .add("case $S: ", entity.getName())
                    .add(returnWrap(transformDTOBlock(new VirtualSourceField(entity, target.getTypeName()), fetchBlock)));
        }

        return getDefaultSpecBuilder(METHOD_NAME, wrapFetcher(wrapList(processedSchema.getUnion(target.getTypeName()).getGraphClassName())))
                .beginControlFlow(
                        "return $N -> (($T) $N.getArgument($S)).stream().map($L ->",
                        VARIABLE_ENV,
                        wrapList(getObjectMapTypeName()),
                        VARIABLE_ENV,
                        FEDERATION_REPRESENTATIONS_ARGUMENT.getName(),
                        VARIABLE_INTERNAL_ITERATION
                )
                .addCode(declare(CONTEXT_NAME, CodeBlock.of("new $T($N)$L", ENVIRONMENT_HANDLER.className, VARIABLE_ENV, asMethodCall(METHOD_CONTEXT_NAME))))
                .beginControlFlow("switch (($T) $N.get($S))", STRING.className, VARIABLE_INTERNAL_ITERATION, TYPE_NAME.getName())
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
