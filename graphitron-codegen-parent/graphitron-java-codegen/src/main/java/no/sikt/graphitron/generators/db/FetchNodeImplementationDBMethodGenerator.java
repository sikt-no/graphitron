package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asNodeQueryName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getStringSetTypeName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapStringMap;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_SELECT;
import static no.sikt.graphitron.mappings.JavaPoetClassName.RECORD2;
import static no.sikt.graphitron.mappings.JavaPoetClassName.SELECTION_SET;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessageAndThrow;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

/**
 * Generator that creates the data fetching methods for the node resolver.
 */
public class FetchNodeImplementationDBMethodGenerator extends FetchDBMethodGenerator {
    public FetchNodeImplementationDBMethodGenerator(ObjectField source, ProcessedSchema processedSchema) {
        super(source, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var implementation = processedSchema.getObject(target.getTypeName());
        if (implementation == null || implementation.getTable() == null) {
            addErrorMessageAndThrow("Type %s needs to have the @%s directive set to be able to implement interface %s",
                    target.getTypeName(), GenerationDirective.TABLE.getName(), NODE_TYPE.getName());
        }
        var implementationTableObject = implementation.getTable();

        var context = new FetchContext(processedSchema, target, implementation, false);
        var selectCode = generateSelectRow(context);

        var argument = target.getNonReservedArguments().get(0);
        var argumentName = argument.getName() + "s";
        var querySource = context.renderQuerySource(implementationTableObject);

        CodeBlock id;
        CodeBlock whereCondition;
        if (GeneratorConfig.shouldMakeNodeStrategy()) {
            id = CodeBlock.of("$L,\n$L", createNodeIdBlock(implementation, context.getTargetAlias()), selectCode);
            whereCondition = hasIdsBlock(implementation, context.getTargetAlias());
        } else {
            var hasOrIn = argument.isID()
                    ? CodeBlock.of("has$N", StringUtils.capitalize(argumentName))
                    : CodeBlock.of("$L.in", implementation.getFieldByName(argument.getName()).getUpperCaseName());

            id = CodeBlock.of("$L.getId(),\n$L", querySource, selectCode);
            whereCondition = CodeBlock.of("$L.$L($N)", querySource, hasOrIn, argumentName);
        }

        return getDefaultSpecBuilder(asNodeQueryName(implementation.getName()), wrapStringMap(implementation.getGraphClassName()))
                .addParameter(getStringSetTypeName(), argumentName)
                .addParameter(SELECTION_SET.className, VARIABLE_SELECT)
                .addCode(createAliasDeclarations(context.getAliasSet()))
                .addCode("return $N\n", VariableNames.CONTEXT_NAME)
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
        if (!(processedSchema.implementsNode(getSource()))) {
            return List.of();
        }

        return List.of(generate(getSource()));
    }
}
