package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.getSelectKeyColumn;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.getSelectKeyColumnRow;
import static no.sikt.graphitron.generators.codebuilding.KeyWrapper.getKeyRowTypeName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asCountMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapMap;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapSet;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.CONTEXT_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

/**
 * Generator that creates methods for counting all available elements for a type.
 */
public class FetchCountDBMethodGenerator extends FetchDBMethodGenerator {
    public static final String UNION_COUNT_QUERY = "unionCountQuery";
    public static final String COUNT_FIELD_NAME = "$count";

    public FetchCountDBMethodGenerator(ObjectField source, ProcessedSchema processedSchema) {
        super(source, processedSchema);
    }

    /**
     * @param target A {@link ObjectField} for which a method should be generated for.
     *                       This must reference an object with the
     *                       "{@link GenerationDirective#TABLE table}" directive set.
     * @return The complete javapoet {@link MethodSpec} based on the provided reference field.
     */
    @Override
    public MethodSpec generate(ObjectField target) {
        var parser = new InputParser(target, processedSchema);
        if (processedSchema.isMultiTableInterface(target.getTypeName()) || processedSchema.isUnion(target.getTypeName())) {
            return getSpecBuilder(target, parser)
                    .addCode(getCodeForMultitableCountMethod(target))
                    .build();
        }
        var context = new FetchContext(processedSchema, target, getSourceContainer(), true);
        var targetSource = context.renderQuerySource(getSourceTable());
        var where = formatWhereContents(context, resolverKeyParamName, isRoot, target.isResolver());
        var nextContext = target.isResolver() ? context.nextContext(target) : context;
        return getSpecBuilder(target, parser)
                .addCode(declareAllServiceClassesInAliasSet(nextContext.getAliasSet()))
                .addCode(createAliasDeclarations(nextContext.getAliasSet()))
                .addCode("return $N\n", CONTEXT_NAME)
                .indent()
                .indent()
                .addCode(".select(")
                .addCodeIf(!isRoot,() -> CodeBlock.of("$L, ",getSelectKeyColumnRow(context)))
                .addCode("$T.count())\n", DSL.className)
                .addCode(".from($L)\n", targetSource)
                .addCode(createSelectJoins(nextContext.getJoinSet()))
                .addCode(where)
                .addCode(createSelectConditions(nextContext.getConditionList(), !where.isEmpty()))
                .addCodeIf(!isRoot,() -> CodeBlock.of(".groupBy($L)\n", getSelectKeyColumn(context)))
                .addStatementIf(isRoot, ".fetchOne(0, $T.class)", INTEGER.className)
                .addStatementIf(!isRoot, ".fetchMap(r -> r.value1().valuesRow(), $T::value2)", RECORD2.className)
                .unindent()
                .unindent()
                .build();
    }

    private CodeBlock getCodeForMultitableCountMethod(ObjectField target) {
        var code = CodeBlock.builder();
        var implementations = processedSchema.getTypesFromInterfaceOrUnion(target.getTypeName());

        if (target.isResolver()) {
            implementations
                    .stream()
                    .findFirst()
                    .map(it -> new FetchContext(processedSchema, new VirtualSourceField(it, target), getSourceContainer(), false))
                    .map(FetchContext::getAliasSet)
                    .ifPresent(it -> code.add(createAliasDeclarations(it)));
        }

        implementations.forEach(implementation -> {
            var virtualTarget = new VirtualSourceField(implementation, target);
            var context = new FetchContext(processedSchema, virtualTarget, getSourceContainer(), true);
            var refContext = virtualTarget.isResolver() ? context.nextContext(virtualTarget) : context;
            var where = formatWhereContents(context, resolverKeyParamName, isRoot, target.isResolver());
            var countForImplementation = CodeBlock.builder()
                    .add("$T.select(", DSL.className)
                    .addIf(!isRoot,() -> CodeBlock.of("$L)",getSelectKeyColumnRow(context)))
                    .addIf(isRoot, "$T.count().as($S))", DSL.className, COUNT_FIELD_NAME)
                    .add("\n.from($L)\n", context.getTargetAlias())
                    .add(createSelectJoins(refContext.getJoinSet()))
                    .add(where)
                    .add(createSelectConditions(context.getConditionList(), !where.isEmpty()));

            var aliasesToDeclare = !target.isResolver() ? refContext.getAliasSet() :
                    context.getAliasSet().stream().findFirst()
                            .map(startAlias -> context.getAliasSet().stream().filter(it -> !it.equals(startAlias)).collect(Collectors.toSet()))
                            .orElse(refContext.getAliasSet());

            code
                    .add(createAliasDeclarations(aliasesToDeclare))
                    .declare(getCountVariableName(implementation.getName()), countForImplementation.build());
        });

        var unionQuery = implementations.stream()
                .map(AbstractObjectDefinition::getName)
                .reduce("", (currString, element) ->
                        String.format(currString.isEmpty() ? "%s" : "%s\n.unionAll(%s)", getCountVariableName(element), currString));

        var resolverKey = implementations.stream()
                .findFirst()
                .map(it -> new FetchContext(processedSchema, new VirtualSourceField(it, target), getSourceContainer(), true))
                .map(FetchContext::getResolverKey);

        return code
                .declare(UNION_COUNT_QUERY, "$L\n.asTable()", unionQuery)
                .add("\nreturn $N.select(", CONTEXT_NAME)
                .addIf(!isRoot,() -> CodeBlock.of("$N.field(0), $T.count())", UNION_COUNT_QUERY, DSL.className))
                .addIf(isRoot, "$T.sum($N.field($S, $T.class)))", DSL.className, UNION_COUNT_QUERY, COUNT_FIELD_NAME, INTEGER.className)
                .add("\n.from($N)", UNION_COUNT_QUERY)
                .addStatementIf(isRoot, "\n.fetchOne(0, $T.class)", INTEGER.className)
                .addIf(!isRoot, "\n.groupBy($N.field(0))", UNION_COUNT_QUERY)
                .addStatementIf(!isRoot && resolverKey.isPresent(), () -> CodeBlock.of("\n.fetchMap(r -> (($T) r.value1()).valuesRow(), $T::value2)", resolverKey.get().getRecordTypeName(), RECORD2.className))
                .build();
    }

    private static String getCountVariableName(String implementationName) {
        return String.format("count%s", implementationName);
    }

    @NotNull
    private MethodSpec.Builder getSpecBuilder(ObjectField referenceField, InputParser parser) {
        return getDefaultSpecBuilder(
                asCountMethodName(referenceField.getName(), getSourceContainer().getName()),
                isRoot ? INTEGER.className : wrapMap(getKeyRowTypeName(referenceField, processedSchema), INTEGER.className)
        )
                .addParameterIf(!isRoot, () -> wrapSet(getKeyRowTypeName(referenceField, processedSchema)), resolverKeyParamName)
                .addParameters(getMethodParameters(parser))
                .addParameters(getContextParameters(referenceField));
    }

    @Override
    public List<MethodSpec> generateAll() {
        var source = getSource();
        if (!source.isGeneratedWithResolver()) {
            return List.of();
        }
        if (!source.hasRequiredPaginationFields()) {
            return List.of();
        }
        if (source.hasServiceReference()) {
            return List.of();
        }

        var generated = generate(source);
        if (generated.code().isEmpty()) {
            return List.of();
        }
        return List.of(generated);
    }
}
