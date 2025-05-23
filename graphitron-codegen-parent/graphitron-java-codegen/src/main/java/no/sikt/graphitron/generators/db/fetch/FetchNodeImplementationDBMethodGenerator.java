package no.sikt.graphitron.generators.db.fetch;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asNodeQueryName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getStringSetTypeName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapStringMap;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_SELECT;
import static no.sikt.graphitron.mappings.JavaPoetClassName.RECORD2;
import static no.sikt.graphitron.mappings.JavaPoetClassName.SELECTION_SET;
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
            throw new IllegalArgumentException(String.format("Type %s needs to have the @%s directive set to be able to implement interface %s", implementation.getName(), GenerationDirective.TABLE.getName(), NODE_TYPE.getName()));
        }

        var virtualReference = new VirtualSourceField(getLocalObject(), target.getTypeName());
        var context = new FetchContext(processedSchema, virtualReference, implementation, false);
        var selectCode = generateSelectRow(context);

        var argument = target.getArguments().get(0);
        var argumentName = argument.getName() + "s";
        var querySource = context.renderQuerySource(implementationTableObject);

        CodeBlock id;
        CodeBlock whereCondition;
        if (GeneratorConfig.shouldMakeNodeStrategy()) {
            id = CodeBlock.of("$L,\n$L", createNodeIdBlock(localObject, context.getTargetAlias()), selectCode);
            whereCondition = hasIdsBlock(localObject, context.getTargetAlias());
        } else {
            var hasOrIn = argument.isID()
                    ? CodeBlock.of("has$N", StringUtils.capitalize(argumentName))
                    : CodeBlock.of("$L.in", implementation.getFieldByName(argument.getName()).getUpperCaseName());

            id = CodeBlock.of("$L.getId(),\n$L", querySource, selectCode);
            whereCondition = CodeBlock.of("$L.$L($N)", querySource, hasOrIn, argumentName);
        }

        var code = CodeBlock.builder()
                .add(createAliasDeclarations(context.getAliasSet()))
                .add("return $N\n", VariableNames.CONTEXT_NAME)
                .indent()
                .indent()
                .add(".select(")
                .add(indentIfMultiline(id))
                .add(")\n.from($L)\n", querySource)
                .add(createSelectJoins(context.getJoinSet()))
                .add(".where($L)\n", whereCondition)
                .add(createSelectConditions(context.getConditionList(), true))
                .addStatement(".$L($T::value1, $T::value2)",
                        (!target.isIterableWrapped() ? "fetchMap" : "fetchGroups"),
                        RECORD2.className,
                        RECORD2.className
                )
                .unindent()
                .unindent();

        return getDefaultSpecBuilder(asNodeQueryName(implementation.getName()), wrapStringMap(implementation.getGraphClassName()))
                .addParameter(getStringSetTypeName(), argumentName)
                .addParameter(SELECTION_SET.className, VARIABLE_SELECT)
                .addCode(code.build())
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return objectFieldsReturningNode
                .stream()
                .filter(entry -> getLocalObject().implementsInterface(NODE_TYPE.getName()))
                .sorted(Comparator.comparing(AbstractField::getName))
                .map(this::generate)
                .collect(Collectors.toList());
    }
}
