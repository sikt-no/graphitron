package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.mapping.AliasWrapper;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.getSelectKeyColumn;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.getSelectKeyColumnRow;
import static no.sikt.graphitron.generators.codebuilding.KeyWrapper.getKeyRowTypeName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asCountMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapMap;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapSet;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_CONTEXT;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_RECORD_ITERATOR;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

/**
 * Generator that creates methods for counting all available elements for a type.
 */
public class FetchCountDBMethodGenerator extends FetchDBMethodGenerator {

    public static final String UNION_COUNT_QUERY = "unionCountQuery";
    public static final String COUNT_FIELD_NAME = "$count";

    public FetchCountDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
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

        var context = new FetchContext(processedSchema, target, getLocalObject(), true);
        var targetSource = context.renderQuerySource(getLocalTable());
        var where = formatWhereContents(context, resolverKeyParamName, isRoot, target.isResolver(), false);
        var nextContext = isIterableWrappedResolverWithPagination(target) ? context.nextContext(target) : context;

        return getSpecBuilder(target, parser)
                .addCode(declareAllServiceClassesInAliasSet(nextContext.getAliasSet()))
                .addCode(createAliasDeclarations(nextContext.getAliasSet()))
                .addCode("return $N\n", VAR_CONTEXT)
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
                .addStatementIf(!isRoot, ".fetchMap($1L -> $1N.value1().valuesRow(), $2T::value2)", VAR_RECORD_ITERATOR, RECORD2.className)
                .unindent()
                .unindent()
                .build();
    }

    private CodeBlock getCodeForMultitableCountMethod(ObjectField target) {
        var implementations = processedSchema.getTypesFromInterfaceOrUnion(target.getTypeName());

        var aliasSet = new LinkedHashSet<AliasWrapper>();
        var codeForImplementations = CodeBlock.builder();

        if (target.isResolver()) {
            implementations
                    .stream()
                    .findFirst()
                    .map(it -> new FetchContext(processedSchema, new VirtualSourceField(it, target), localObject, false))
                    .map(FetchContext::getAliasSet)
                    .ifPresent(aliasSet::addAll);
        }

        implementations.forEach(implementation -> {
            var virtualTarget = new VirtualSourceField(implementation, target);
            var context = new FetchContext(processedSchema, virtualTarget, localObject, true);
            var refContext = isIterableWrappedResolverWithPagination(virtualTarget)
                             ? context.nextContext(virtualTarget)
                             : context;
            var where = formatWhereContents(context, resolverKeyParamName, isRoot, target.isResolver(), false);
            var countForImplementation = CodeBlock.builder()
                    .add("$T.select(", DSL.className)
                    .addIf(!isRoot,() -> CodeBlock.of("$L)",getSelectKeyColumnRow(context)))
                    .addIf(isRoot, "$T.count().as($S))", DSL.className, COUNT_FIELD_NAME)
                    .add("\n.from($L)\n", isIterableWrappedResolverWithPagination(virtualTarget)
                                          ? context.getTargetAlias()
                                          : context.getSourceAlias())
                    .add(createSelectJoins(refContext.getJoinSet()))
                    .add(where)
                    .add(createSelectConditions(context.getConditionList(), !where.isEmpty()));

            if (!isIterableWrappedResolverWithPagination(target)) {
                aliasSet.addAll(refContext.getAliasSet());
            } else {
                aliasSet.addAll(context.getAliasSet().stream().findFirst()
                       .map(startAlias -> context.getAliasSet().stream().filter(
                               it -> !it.equals(startAlias)).collect(Collectors.toSet()))
                       .orElse(refContext.getAliasSet()));
            }

            codeForImplementations.declare(getCountVariableName(implementation.getName()), countForImplementation.build());
        });

        var code = CodeBlock
                .builder()
                .add(createAliasDeclarations(aliasSet))
                .add("\n")
                .add(codeForImplementations.build());

        var resolverKey = implementations.stream()
                .findFirst()
                .map(it -> new FetchContext(processedSchema, new VirtualSourceField(it, target), localObject, true))
                .map(FetchContext::getResolverKey);

        var unionQuery = implementations.stream()
                .map(AbstractObjectDefinition::getName)
                .reduce("", (currString, element) ->
                        String.format(currString.isEmpty() ? "%s" : "%s\n.unionAll(%s)", getCountVariableName(element), currString));

        return code
                .declare(UNION_COUNT_QUERY, "$L\n.asTable()", unionQuery)
                .add("\nreturn $N.select(", VAR_CONTEXT)
                .addIf(!isRoot,() -> CodeBlock.of("$N.field(0), $T.count())", UNION_COUNT_QUERY, DSL.className))
                .addIf(isRoot, "$T.sum($N.field($S, $T.class)))", DSL.className, UNION_COUNT_QUERY, COUNT_FIELD_NAME, INTEGER.className)
                .add("\n.from($N)", UNION_COUNT_QUERY)
                .addStatementIf(isRoot, "\n.fetchOne(0, $T.class)", INTEGER.className)
                .addIf(!isRoot, "\n.groupBy($N.field(0))", UNION_COUNT_QUERY)
                .addStatementIf(
                        !isRoot && resolverKey.isPresent(),
                        () -> CodeBlock.of(
                                "\n.fetchMap($1L -> (($2T) $1N.value1()).valuesRow(), $3T::value2)",
                                VAR_RECORD_ITERATOR,
                                resolverKey.get().getRecordTypeName(),
                                RECORD2.className
                        )
                )
                .build();
    }

    private static String getCountVariableName(String implementationName) {
        return String.format("count%s", implementationName);
    }

    @NotNull
    private MethodSpec.Builder getSpecBuilder(ObjectField referenceField, InputParser parser) {
        return getDefaultSpecBuilder(
                asCountMethodName(referenceField.getName(), getLocalObject().getName()),
                isRoot ? INTEGER.className : wrapMap(getKeyRowTypeName(referenceField, processedSchema), INTEGER.className)
        )
                .addParameterIf(!isRoot, () -> wrapSet(getKeyRowTypeName(referenceField, processedSchema)), resolverKeyParamName)
                .addParameters(getMethodParameters(parser))
                .addParameters(getContextParameters(referenceField));
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(ObjectField::hasRequiredPaginationFields)
                .filter(it -> !it.hasServiceReference())
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}
