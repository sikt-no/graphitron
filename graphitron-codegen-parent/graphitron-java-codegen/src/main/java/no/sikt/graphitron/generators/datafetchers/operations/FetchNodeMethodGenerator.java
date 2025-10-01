package no.sikt.graphitron.generators.datafetchers.operations;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.Comparator;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.getQueryClassName;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.newDataFetcher;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFetcher;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFuture;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.ILLEGAL_ARGUMENT_EXCEPTION;
import static no.sikt.graphitron.mappings.JavaPoetClassName.NODE_ID_HANDLER;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

/**
 * Generates resolvers for the Node interface.
 */
public class FetchNodeMethodGenerator extends DataFetcherMethodGenerator {
    private final static String VARIABLE_LOADER = "_loaderName";

    public FetchNodeMethodGenerator(ObjectField source, ProcessedSchema processedSchema) {
        super(source, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var inputField = target.getArguments().get(0);
        var inputFieldName = inputField.getName();

        var interfaceDefinition = processedSchema.getInterface(target);
        var implementations = processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(it -> it.implementsInterface(interfaceDefinition.getName()))
                .sorted(Comparator.comparing(AbstractObjectDefinition::getName))
                .toList();
        var anyImplementation = implementations.stream().findFirst();
        var targetBlock = CodeBlock.builder();
        if (anyImplementation.isPresent() && interfaceDefinition.getName().equals(NODE_TYPE.getName())) {  // Node special case.
            if (GeneratorConfig.shouldMakeNodeStrategy()) {
                targetBlock.add("$N.getTypeId($N)", NODE_ID_STRATEGY_NAME, inputFieldName);
            } else {
                targetBlock.add("$N.get($N.getTable($N).getName())", NODE_MAP_NAME, NODE_ID_HANDLER_NAME, inputFieldName);
            }
        } else {
            targetBlock.add("null");
        }

        var illegalBlock = CodeBlock.statementOf(
                "throw new $T(\"Could not resolve input $N with value \" + $N + \" within type \" + $N)",
                ILLEGAL_ARGUMENT_EXCEPTION.className,
                inputFieldName,
                inputFieldName,
                GeneratorConfig.shouldMakeNodeStrategy() ? VARIABLE_TYPE_ID : VARIABLE_TYPE_NAME
        );

        dataFetcherWiring.add(new WiringContainer(target.getName(), getSourceContainer().getName(), target.getName()));

        return getDefaultSpecBuilder(target.getName(), wrapFetcher(wrapFuture(interfaceDefinition.getGraphClassName())))
                .beginControlFlow("return $N ->", VARIABLE_ENV)
                .addCode(extractParams(target))
                .declare(GeneratorConfig.shouldMakeNodeStrategy() ? VARIABLE_TYPE_ID : VARIABLE_TYPE_NAME, targetBlock.build())
                .beginControlFlow("if ($N == null)", GeneratorConfig.shouldMakeNodeStrategy() ? VARIABLE_TYPE_ID : VARIABLE_TYPE_NAME)
                .addCode(illegalBlock)
                .endControlFlow()
                .declare(VARIABLE_LOADER, CodeBlock.of("$N + $S", GeneratorConfig.shouldMakeNodeStrategy() ? VARIABLE_TYPE_ID : VARIABLE_TYPE_NAME, asInternalName(target.getName())))
                .declare(VARIABLE_FETCHER_NAME, newDataFetcher())
                .addCode("\n")
                .beginControlFlow("switch ($N)", GeneratorConfig.shouldMakeNodeStrategy() ? VARIABLE_TYPE_ID : VARIABLE_TYPE_NAME)
                .addParameterIf(
                        !GeneratorConfig.shouldMakeNodeStrategy() && interfaceDefinition.getName().equals(NODE_TYPE.getName()),
                        NODE_ID_HANDLER.className,
                        NODE_ID_HANDLER_NAME
                )
                .addCode(
                        implementations
                                .stream()
                                .map(implementation -> codeForImplementation(implementation, inputFieldName))
                                .collect(CodeBlock.joining())
                )
                .addCode("default: $L", illegalBlock)
                .endControlFlow()
                .endControlFlow("") // Keep this, logic to set semicolon only kicks in if a string is set.
                .build();
    }

    private CodeBlock codeForImplementation(ObjectDefinition implementation, String inputFieldName) {
        var implementationTypeName = implementation.getName();
        return CodeBlock.statementOf(
                "case $S: return $N.loadInterface($N, $N, ($L, $L, $L) -> $T.$L($N, $L$N, $N))",
                GeneratorConfig.shouldMakeNodeStrategy() ? implementation.getTypeId() : implementationTypeName,

                VARIABLE_FETCHER_NAME,

                VARIABLE_LOADER,
                inputFieldName,

                CONTEXT_NAME,
                IDS_NAME,
                SELECTION_SET_NAME,

                getQueryClassName(getFormatGeneratedName(getSourceContainer().getName() + getSource().getTypeName(), implementationTypeName) + DBClassGenerator.FILE_NAME_SUFFIX),
                asNodeQueryName(implementationTypeName),

                CONTEXT_NAME,
                CodeBlock.ofIf(GeneratorConfig.shouldMakeNodeStrategy(), "$N, ", NODE_ID_STRATEGY_NAME),
                IDS_NAME,
                SELECTION_SET_NAME
        );
    }

    @Override
    public List<MethodSpec> generateAll() {
        var source = getSource();
        if (!source.isGeneratedWithResolver()) {
            return List.of();
        }
        if (!processedSchema.isInterface(source.getTypeName()) || !source.getTypeName().equals(NODE_TYPE.getName())) {
            return List.of();
        }

        var generated = generate(source);
        if (generated.code().isEmpty()) {
            return List.of();
        }
        return List.of(generated);
    }
}
