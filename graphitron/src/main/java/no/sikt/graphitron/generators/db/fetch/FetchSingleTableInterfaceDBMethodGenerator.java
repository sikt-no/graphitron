package no.sikt.graphitron.generators.db.fetch;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.ORDER_FIELDS_NAME;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_INTERNAL_ITERATION;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

public class FetchSingleTableInterfaceDBMethodGenerator extends FetchDBMethodGenerator {

    public static final String DISCRIMINATOR = "_discriminator";
    public static final String DISCRIMINATOR_VALUE = "_discriminatorValue";
    public static final String TOKEN = "_token";

    public FetchSingleTableInterfaceDBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema
    ) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var parser = new InputParser(target, processedSchema);

        var interfaceDefinition = processedSchema.getInterface(target);
        var implementations = processedSchema.getImplementationsForInterface(interfaceDefinition);

        return getSpecBuilder(target, interfaceDefinition.getGraphClassName(), parser)
                .addCode(implementations.isEmpty() ? CodeBlock.of("return null;") : getCode(target, implementations))
                .build();
    }

    private CodeBlock getCode(ObjectField target, Set<ObjectDefinition> implementations) {
        var context = new FetchContext(processedSchema, target, getLocalObject(), false);
        var selectCode = generateSelectRow(context, target, implementations);
        var querySource = context.getTargetAlias();
        var whereBlock = formatWhereContents(context, idParamName, isRoot, target.isResolver());
        var fetchAndMap = fetchAndMap(target, implementations, querySource);

        Optional<CodeBlock> maybeOrderFields = maybeCreateOrderFieldsDeclarationBlock(target, context.getTargetAlias(), context.getTargetTableName());

        return CodeBlock.builder()
                .add(createAliasDeclarations(context.getAliasSet()))
                .add(maybeOrderFields.orElse(empty()))
                .add("return $N.select($L)", VariableNames.CONTEXT_NAME, indentIfMultiline(selectCode))
                .add("\n.from($L)\n", querySource)
                .add(createSelectJoins(context.getJoinSet()))
                .add("\n")
                .add(whereBlock)
                .add(maybeOrderFields
                        .map(it -> CodeBlock.of("\n.orderBy($L)", ORDER_FIELDS_NAME))
                        .orElse(empty()))
                .add(target.hasForwardPagination() ? createSeekAndLimitBlock() : empty())
                .add(fetchAndMap)
                .build();
    }

    protected CodeBlock generateSelectRow(FetchContext context, ObjectField target, Set<ObjectDefinition> implementations) {
        List<GenerationField> allFields = context
                .getReferenceObject()
                .getFields()
                .stream()
                .filter(f -> !(f.isResolver() && (processedSchema.isObject(f) || processedSchema.isInterface(f))))
                .collect(Collectors.toList());

        implementations.forEach(impl ->
                impl.getFields().stream()
                        .filter(f -> allFields.stream().map(FieldSpecification::getName).noneMatch(it -> it.equals(f.getName())))
                        .filter(f -> !(f.isResolver() && (processedSchema.isObject(f))))
                        .forEach(allFields::add));

        var rowElements = new ArrayList<CodeBlock>();
        rowElements.add(CodeBlock.builder()
                .add(CodeBlock.of("$L.$N", context.getTargetAlias(), processedSchema.getInterface(target).getDiscriminatorFieldName()))
                .add(".as($S)", DISCRIMINATOR).build());

        if (target.hasForwardPagination()) {
            rowElements.add(CodeBlock.of("$T.getOrderByToken($L, $L).as($S)",
                    QUERY_HELPER.className, context.getTargetAlias(), ORDER_FIELDS_NAME, TOKEN));
        }

        for (var field : allFields) {
            rowElements.add(CodeBlock.of("$L.as($S)", getSelectCodeAndFieldSource(field, context).getLeft(), field.getName()));
        }
        return CodeBlock.join(rowElements, ",\n");
    }

    private CodeBlock fetchAndMap(ObjectField target, Set<ObjectDefinition> implementations, String querySource) {
        var interfaceDefinition = processedSchema.getInterface(target);

        var mapping = CodeBlock.builder()
                .indent()
                .beginControlFlow("$N -> ", VARIABLE_INTERNAL_ITERATION)
                .add(declare(DISCRIMINATOR_VALUE,
                        CodeBlock.of("$N.get($S, $L.$L.getConverter())",
                                VARIABLE_INTERNAL_ITERATION, DISCRIMINATOR, querySource, interfaceDefinition.getDiscriminatorFieldName())))
                .beginControlFlow("switch ($N)", DISCRIMINATOR_VALUE
                );

        for (var implementation : implementations) {
            mapping.add("case $S:\n", implementation.getDiscriminator())
                    .indent()
                    .add("return ")
                    .add(target.hasForwardPagination() ? CodeBlock.of("$T.of($N.get($S, $T.class), ", PAIR.className, VARIABLE_INTERNAL_ITERATION, TOKEN, STRING.className) : empty())
                    .add("$N.into($T.class)$L;\n", VARIABLE_INTERNAL_ITERATION, implementation.getGraphClassName(), target.hasForwardPagination() ? ")" : "")
                    .unindent();
        }
        mapping.add("default:\n")
                .indent()
                .add("throw new $T($T.format($S, \"$T\", $N));\n",
                        RuntimeException.class,
                        String.class,
                        "Querying interface '%s' returned row with unexpected discriminator value '%s'",
                        interfaceDefinition.getGraphClassName(),
                        DISCRIMINATOR_VALUE)
                .unindent()
                .endControlFlow()
                .endControlFlow()
                .unindent();

        return CodeBlock.of("\n.$L(\n$L\n);",
                target.isIterableWrapped() || target.hasForwardPagination() ? "fetch" : "fetchOne",
                mapping.build());
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .filter(processedSchema::isInterface)
                .filter(it -> !it.getTypeName().equals(NODE_TYPE.getName()))
                .filter(it -> processedSchema.getInterface(it.getTypeName()).hasDiscriminator())
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}
