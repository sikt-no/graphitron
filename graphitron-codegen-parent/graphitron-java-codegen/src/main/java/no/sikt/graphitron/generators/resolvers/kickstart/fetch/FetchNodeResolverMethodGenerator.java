package no.sikt.graphitron.generators.resolvers.kickstart.fetch;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.KickstartResolverMethodGenerator;
import no.sikt.graphitron.generators.dependencies.IdHandlerDependency;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.DATA_FETCHING_ENVIRONMENT;
import static no.sikt.graphitron.mappings.JavaPoetClassName.ILLEGAL_ARGUMENT_EXCEPTION;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Generates resolvers for queries returning an interface. E.g. the node resolver.
 */
public class FetchNodeResolverMethodGenerator extends KickstartResolverMethodGenerator {
    private final ObjectDefinition localObject;
    private final static String VARIABLE_LOADER = "_loaderName";

    public FetchNodeResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
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
            dependencyMap.computeIfAbsent(target.getName(), s -> new ArrayList<>()).add(IdHandlerDependency.getInstance());
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

        var spec = getDefaultSpecBuilder(target.getName(), interfaceDefinition.getGraphClassName());
        if (!localObject.isOperationRoot()) {
            spec.addParameter(localObject.getGraphClassName(), uncapitalize(localObject.getName()));
        }
        spec
                .addParameter(iterableWrapType(inputField), inputFieldName)
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
                .addCode(declare(VARIABLE_TYPE_NAME, targetBlock.build()))
                .beginControlFlow("if ($N == null)", VARIABLE_TYPE_NAME)
                .addCode(illegalBlock)
                .endControlFlow()
                .addCode(declare(VARIABLE_LOADER, CodeBlock.of("$N + $S", VARIABLE_TYPE_NAME, asInternalName(target.getName()))))
                .addCode(declare(VARIABLE_FETCHER_NAME, newDataFetcher()))
                .addCode("\n")
                .beginControlFlow("switch ($N)", VARIABLE_TYPE_NAME);

        implementations
                .stream()
                .map(implementation -> codeForImplementation(implementation.getName(), inputFieldName))
                .forEach(spec::addCode);

        return spec.addCode("default: $L", illegalBlock).endControlFlow().build();
    }

    private CodeBlock codeForImplementation(String implementationTypeName, String inputFieldName) {
        var queryLocation = asQueryClass(implementationTypeName);
        var queryClass = getQueryClassName(queryLocation);
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

    @Override
    public boolean generatesAll() {
        return localObject
                .getFields()
                .stream()
                .filter(it -> processedSchema.isInterface(it.getTypeName()))
                .allMatch(ObjectField::isGeneratedWithResolver);
    }
}
