package no.sikt.graphitron.generators.datafetchers.operations;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFetcher;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFuture;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphitron.mappings.JavaPoetClassName.ILLEGAL_ARGUMENT_EXCEPTION;
import static no.sikt.graphitron.mappings.JavaPoetClassName.NODE_ID_HANDLER;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

/**
 * Generates resolvers for the Node interface.
 */
public class FetchNodeMethodGenerator extends DataFetcherMethodGenerator {
    private final ObjectDefinition localObject;

    public FetchNodeMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
        this.localObject = localObject;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var inputField = target.getArguments().get(0);
        var inputFieldName = inputField.getName();
        var prefixedInputFieldName = inputPrefix(inputFieldName);

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
                targetBlock.add("$N.getTypeId($N)", VAR_NODE_STRATEGY, prefixedInputFieldName);
            } else {
                targetBlock.add("$N.get($N.getTable($N).getName())", VAR_NODE_MAP, VAR_NODE_HANDLER, prefixedInputFieldName);
            }
        } else {
            targetBlock.add("null");
        }

        var illegalBlock = CodeBlock.statementOf(
                "throw new $T(\"Could not resolve input $N with value \" + $N + \" within type \" + $N)",
                ILLEGAL_ARGUMENT_EXCEPTION.className,
                inputFieldName,
                prefixedInputFieldName,
                GeneratorConfig.shouldMakeNodeStrategy() ? VAR_TYPE_ID : VAR_TYPE_NAME
        );

        dataFetcherWiring.add(new WiringContainer(target.getName(), getLocalObject().getName(), target.getName()));

        return getDefaultSpecBuilder(target.getName(), wrapFetcher(wrapFuture(interfaceDefinition.getGraphClassName())))
                .beginControlFlow("return $N ->", VAR_ENV)
                .addCode(extractParams(target))
                .declare(GeneratorConfig.shouldMakeNodeStrategy() ? VAR_TYPE_ID : VAR_TYPE_NAME, targetBlock.build())
                .beginControlFlow("if ($N == null)", GeneratorConfig.shouldMakeNodeStrategy() ? VAR_TYPE_ID : VAR_TYPE_NAME)
                .addCode(illegalBlock)
                .endControlFlow()
                .declare(VAR_LOADER, CodeBlock.of("$N + $S", GeneratorConfig.shouldMakeNodeStrategy() ? VAR_TYPE_ID : VAR_TYPE_NAME, "_" + target.getName()))
                .declare(VAR_FETCHER_NAME, newDataFetcher())
                .addCode("\n")
                .beginControlFlow("switch ($N)", GeneratorConfig.shouldMakeNodeStrategy() ? VAR_TYPE_ID : VAR_TYPE_NAME)
                .addParameterIf(
                        !GeneratorConfig.shouldMakeNodeStrategy() && interfaceDefinition.getName().equals(NODE_TYPE.getName()),
                        NODE_ID_HANDLER.className,
                        VAR_NODE_HANDLER
                )
                .addCode(
                        implementations
                                .stream()
                                .map(implementation -> codeForImplementation(implementation, prefixedInputFieldName))
                                .collect(CodeBlock.joining())
                )
                .addCode("default: $L", illegalBlock)
                .endControlFlow()
                .endControlFlow("") // Keep this, logic to set semicolon only kicks in if a string is set.
                .build();
    }

    private CodeBlock codeForImplementation(ObjectDefinition implementation, String inputFieldName) {
        var implementationTypeName = implementation.getName();
        CodeBlock dbFunction = CodeBlock.of(
                "($L, $L, $L) -> $T.$L($L)",
                VAR_CONTEXT,
                VAR_IDS,
                VAR_SELECTION_SET,
                getQueryClassName(asQueryClass(implementationTypeName)),
                asNodeQueryName(implementationTypeName),
                GeneratorConfig.shouldMakeNodeStrategy() ?
                        CodeBlock.of("$N, $N, $N, $N", VAR_CONTEXT, VAR_NODE_STRATEGY, VAR_IDS, VAR_SELECTION_SET)
                        : CodeBlock.of("$N, $N, $N", VAR_CONTEXT, VAR_IDS, VAR_SELECTION_SET)
            );

        String name;
        if (GeneratorConfig.shouldMakeNodeStrategy()) {
            name = implementation.getTypeId();
        } else {
            name = implementationTypeName;
        }

        return CodeBlock.statementOf("case $S: return $N.$L($N, $N, $L)", name, VAR_FETCHER_NAME, "loadInterface", VAR_LOADER, inputFieldName, dbFunction);
    }

    @Override
    public List<MethodSpec> generateAll() {
        return localObject
                .getFields()
                .stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(it -> processedSchema.isInterface(it.getTypeName()) && it.getTypeName().equals(NODE_TYPE.getName()))
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}
