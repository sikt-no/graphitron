package no.sikt.graphitron.generators.resolvers.datafetchers.fetch;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DBClassGenerator;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asNodeQueryName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.ILLEGAL_ARGUMENT_EXCEPTION;
import static no.sikt.graphitron.mappings.JavaPoetClassName.NODE_ID_HANDLER;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

/**
 * Generates resolvers for the Node interface.
 */
public class FetchNodeMethodGenerator extends DataFetcherMethodGenerator {
    private final ObjectDefinition localObject;
    private final static String VARIABLE_LOADER = "_loaderName";

    public FetchNodeMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
        this.localObject = localObject;
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
            targetBlock.add("$N.get($N.getTable($N).getName())", NODE_MAP_NAME, NODE_ID_HANDLER_NAME, inputFieldName);
        } else {
            targetBlock.add("null");
        }

        var illegalBlock = CodeBlock.builder().addStatement(
                "throw new $T(\"Could not resolve input $N with value \" + $N + \" within type \" + $N)",
                ILLEGAL_ARGUMENT_EXCEPTION.className,
                inputFieldName,
                inputFieldName,
                VARIABLE_TYPE_NAME
        ).build();

        dataFetcherWiring.add(new WiringContainer(target.getName(), getLocalObject().getName(), target.getName()));

        var spec = getDefaultSpecBuilder(target.getName(), wrapFetcher(wrapFuture(interfaceDefinition.getGraphClassName())))
                .beginControlFlow("return $N ->", VARIABLE_ENV)
                .addCode(declareArgs(target))
                .addCode(extractParams(target))
                .addCode(declare(VARIABLE_TYPE_NAME, targetBlock.build()))
                .beginControlFlow("if ($N == null)", VARIABLE_TYPE_NAME)
                .addCode(illegalBlock)
                .endControlFlow()
                .addCode(declare(VARIABLE_LOADER, CodeBlock.of("$N + $S", VARIABLE_TYPE_NAME, "_" + target.getName())))
                .addCode(declare(VARIABLE_FETCHER_NAME, newDataFetcher()))
                .addCode("\n")
                .beginControlFlow("switch ($N)", VARIABLE_TYPE_NAME);

        if (interfaceDefinition.getName().equals(NODE_TYPE.getName())) {
            spec.addParameter(NODE_ID_HANDLER.className, NODE_ID_HANDLER_NAME);
        }

        implementations
                .stream()
                .map(implementation -> codeForImplementation(implementation.getName(), inputFieldName))
                .forEach(spec::addCode);

        return spec
                .addCode("default: $L", illegalBlock)
                .endControlFlow()
                .endControlFlow("") // Keep this, logic to set semicolon only kicks in if a string is set.
                .build();
    }

    private CodeBlock codeForImplementation(String implementationTypeName, String inputFieldName) {
        var queryLocation = asQueryClass(implementationTypeName);
        var queryClass = getGeneratedClassName(DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + FetchDBClassGenerator.SAVE_DIRECTORY_NAME, queryLocation);
        var dbFunction = CodeBlock.of(
                "($L, $L, $L) -> $T.$L($N, $N, $N)",
                CONTEXT_NAME,
                IDS_NAME,
                SELECTION_SET_NAME,
                queryClass,
                asNodeQueryName(implementationTypeName),
                CONTEXT_NAME,
                IDS_NAME,
                SELECTION_SET_NAME
        );

        return CodeBlock
                .builder()
                .addStatement("case $S: return $N.$L($N, $N, $L)", implementationTypeName, VARIABLE_FETCHER_NAME, "loadInterface", VARIABLE_LOADER, inputFieldName, dbFunction)
                .build();
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
