package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.MethodInputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.configuration.GeneratorConfig.optionalSelectIsEnabled;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asNodeQueryName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getStringSetTypeName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapStringMap;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_STRATEGY;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_SELECT;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphitron.mappings.JavaPoetClassName.RECORD2;
import static no.sikt.graphitron.mappings.JavaPoetClassName.SELECTION_SET;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessageAndThrow;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

/**
 * Generator that creates the data fetching methods for interface implementations, e.g. queries used by the node resolver.
 */
public class FetchNodeImplementationDBMethodGenerator extends FetchDBMethodGenerator {
    private final Set<ObjectField> objectFieldsReturningNode;

    public FetchNodeImplementationDBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema,
            Set<ObjectField> objectFieldsReturningNode
    ) {
        super(localObject, processedSchema);
        this.objectFieldsReturningNode = objectFieldsReturningNode;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var implementation = getLocalObject();
        var implementationTableObject = implementation.getTable();
        if (implementationTableObject == null) {
            addErrorMessageAndThrow("Type %s needs to have the @%s directive set to be able to implement interface %s",
                    implementation.getName(), GenerationDirective.TABLE.getName(), NODE_TYPE.getName());
        }

        var virtualReference = new VirtualSourceField(getLocalObject(), target.getTypeName());
        var context = new FetchContext(processedSchema, virtualReference, implementation, false);

        var argument = target.getArguments().get(0);
        var argumentName = inputPrefix(argument.getName());
        var querySource = context.renderQuerySource(implementationTableObject);

        var parser = new MethodInputParser(virtualReference, processedSchema);
        var methodInputs = parser.getMethodInputNames(true, false, true);
        if (shouldMakeNodeStrategy()) methodInputs.add(0, VAR_NODE_STRATEGY);
        if (optionalSelectIsEnabled()) methodInputs.add(VAR_SELECT);
        var selectBlock = CodeBlock.of("$L($L)", generateHelperMethodName(virtualReference), String.join(", ", methodInputs));

        CodeBlock id;
        CodeBlock whereCondition;
        if (shouldMakeNodeStrategy()) {
            id = CodeBlock.of("$L,\n$L", createNodeIdBlock(localObject, context.getTargetAlias()), selectBlock);
            whereCondition = hasIdOrIdsBlock(CodeBlock.of(argumentName), localObject, context.getTargetAlias(), CodeBlock.empty(), true);
        } else {
            var hasOrIn = argument.isID()
                    ? CodeBlock.of("hasIds")
                    : CodeBlock.of("$L.in", implementation.getFieldByName(argument.getName()).getUpperCaseName());

            id = CodeBlock.of("$L.getId(),\n$L", querySource, selectBlock);
            whereCondition = CodeBlock.of("$L.$L($N)", querySource, hasOrIn, argumentName);
        }

        return getDefaultSpecBuilder(asNodeQueryName(implementation.getName()), wrapStringMap(implementation.getGraphClassName()))
                .addParameter(getStringSetTypeName(), argumentName)
                .addParameter(SELECTION_SET.className, VAR_SELECT)
                .addCode(createAliasDeclarations(context.getAliasSet()))
                .addCode("return $N\n", VariableNames.VAR_CONTEXT)
                .indent()
                .indent()
                .addCode(".select($L)\n", indentIfMultiline(id))
                .addCode(".from($L)\n", querySource)
                .addCode(createSelectJoins(context.getJoinSet()))
                .addCode(".where($L)\n", whereCondition)
                .addCode(createSelectConditions(context.getConditionList(), true))
                .addStatement(
                        ".$L($T::value1, $T::value2)",
                        (!target.isIterableWrapped() ? "fetchMap" : "fetchGroups"),
                        RECORD2.className,
                        RECORD2.className
                )
                .unindent()
                .unindent()
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return objectFieldsReturningNode
                .stream()
                .filter(entry -> getLocalObject().implementsInterface(NODE_TYPE.getName()))
                .sorted(Comparator.comparing(AbstractField::getName))
                .map(this::generate)
                .toList();
    }
}
