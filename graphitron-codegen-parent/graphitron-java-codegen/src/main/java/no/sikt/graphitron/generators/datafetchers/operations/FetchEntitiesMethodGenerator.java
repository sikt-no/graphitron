package no.sikt.graphitron.generators.datafetchers.operations;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asEntitiesQueryName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

/**
 * Generates resolver for the Query._entities.
 */
public class FetchEntitiesMethodGenerator extends DataFetcherMethodGenerator {
    private final ObjectDefinition localObject;

    public FetchEntitiesMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
        this.localObject = localObject;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var entitiesUnion = processedSchema.getUnion(FEDERATION_ENTITY_UNION.getName());
        var builder = getDefaultSpecBuilder(target.getName(), wrapFetcher(wrapFuture(wrapList(entitiesUnion.getGraphClassName()))));
        dataFetcherWiring.add(new WiringContainer(target.getName(), getLocalObject().getName(), target.getName()));

        if (processedSchema.getEntities().isEmpty()) {
            return builder.addCode(returnWrap(CodeBlock.of("$N -> return null", VAR_ENV))).build();
        }

        var typeNameToMethodMap = mapOf(
                indentIfMultiline(
                        processedSchema.getEntities()
                                .entrySet().stream()
                                .map(it ->
                                        CodeBlock.of("$S, $L", it.getKey(), methodCallForImplementation(it.getValue())))
                                .collect(CodeBlock.joining(",\n"))
                )
        );

        var arguments = CodeBlock.builder()
                .addIf(shouldMakeNodeStrategy(), "$L,\n", VAR_NODE_STRATEGY)
                .add("$L,\n$L", inputPrefix(FEDERATION_REPRESENTATIONS_ARGUMENT.getName()), typeNameToMethodMap)
                .build();

        var methodCall = CodeBlock.statementOf(
                "return $L.$L($L)",
                newDataFetcher(),
                "loadLookupEntities",
                indentIfMultiline(arguments)
        );

        return builder
                .beginControlFlow("return $N ->", VAR_ENV)
                .addCode(extractParams(target))
                .addCode(declareAllServiceClasses(target.getName()))
                .addCode(methodCall)
                .endControlFlow("") // Keep this, logic to set semicolon only kicks in if a string is set.
                .build();
    }

    private CodeBlock methodCallForImplementation(ObjectDefinition implementation) {
        return CodeBlock.of(
                "($L, $L, $L) -> $T.$L($L)",
                VAR_CONTEXT,
                VAR_REPS,
                VAR_SELECTION_SET,
                getQueryClassName(asQueryClass(implementation.getName())),
                asEntitiesQueryName(implementation.getName()),
                shouldMakeNodeStrategy() ?
                        CodeBlock.of("$N, $N, $N, $N", VAR_CONTEXT, VAR_NODE_STRATEGY, VAR_REPS, VAR_SELECTION_SET)
                        : CodeBlock.of("$N, $N, $N", VAR_CONTEXT, VAR_REPS, VAR_SELECTION_SET)
        );
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (!processedSchema.isFederationImported()) {
            return List.of();
        }
        return localObject
                .getFields()
                .stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(it -> getLocalObject().isOperationRoot())
                .filter(it -> it.getName().equals(FEDERATION_ENTITIES_FIELD.getName()))
                .filter(it -> it.getTypeName().equals(FEDERATION_ENTITY_UNION.getName()))
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}
