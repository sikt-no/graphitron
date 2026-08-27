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

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.KeyWrapper.getKeyTableRecordTypeName;
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
        return isRoot() ? generateForRoot(target, parser) : generateForResolver(target, parser);
    }

    /**
     * Root counts have no parent row to count per, so the whole statement is the counting query and every join can be
     * flattened into it. This is the one shape where the join sequence has no correlated subquery to anchor it, hence
     * the {@code addAllJoinsToJoinSet} flag.
     */
    private MethodSpec generateForRoot(ObjectField target, InputParser parser) {
        var context = new FetchContext(processedSchema, target, getLocalObject(), true);
        var targetSource = context.renderQuerySource(getLocalTable());
        var where = formatWhereContents(context, target.createsDataFetcher());
        var nextContext = target.createsDataFetcher() ? context.nextContext(target) : context;
        return getSpecBuilder(target, parser)
                .addCode(declareAllServiceClassesInAliasSet(nextContext.getAliasSet()))
                .addCode(createAliasDeclarations(nextContext.getAliasSet()))
                .addCode("return $N\n", VAR_CONTEXT)
                .indent()
                .indent()
                .addCode(".select($T.count())\n", DSL.className)
                .addCode(".from($L)\n", targetSource)
                .addCode(createSelectJoins(nextContext.getJoinSet()))
                .addCode(where)
                .addCode(createSelectConditions(nextContext.getConditionList(), !where.isEmpty()))
                .addStatement(".fetchOne(0, $T.class)", INTEGER.className)
                .unindent()
                .unindent()
                .build();
    }

    /**
     * Counts one value per parent row, so the count itself must be a correlated subquery over the referenced table,
     * exactly as the corresponding list query nests its {@code multiset}. Anchoring the subquery on the referenced
     * layer's own source alias is what binds a reference path that starts by aliasing the parent table, which a
     * {@link GenerationDirective#REFERENCE reference} through a condition method with no key does.
     */
    private MethodSpec generateForResolver(ObjectField target, InputParser parser) {
        var context = new FetchContext(processedSchema, target, getLocalObject(), false);
        var nextContext = target.createsDataFetcher() ? context.nextContext(target) : context;

        // Note that these must happen before alias declaration, as filter arguments can bring aliases of their own.
        var countSubquery = generateCorrelatedCountSubquery(nextContext);
        var where = formatWhereContents(context, target.createsDataFetcher());
        var querySource = context.renderQuerySource(getLocalTable());

        return getSpecBuilder(target, parser)
                .addCode(declareAllServiceClassesInAliasSet(context.getAliasSet()))
                .addCode(createAliasDeclarations(context.getAliasSet()))
                .addCode("return $N\n", VAR_CONTEXT)
                .indent()
                .indent()
                .addCode(".select($L, $L)\n", resolverKeyAsTableRecord(context), countSubquery)
                .addCode(".from($L)\n", querySource)
                .addCode(createSelectJoins(context.getJoinSet()))
                .addCode(where)
                .addCode(createSelectConditions(context.getConditionList(), !where.isEmpty()))
                .addStatement(".fetchMap($1T::value1, $1T::value2)", RECORD2.className)
                .unindent()
                .unindent()
                .build();
    }

    private CodeBlock getCodeForMultitableCountMethod(ObjectField target) {
        var implementations = processedSchema.getTypesFromInterfaceOrUnion(target.getTypeName()).orElse(List.of());

        var aliasSet = new LinkedHashSet<AliasWrapper>();
        var codeForImplementations = CodeBlock.builder();

        implementations.forEach(implementation -> {
            var virtualTarget = new VirtualSourceField(implementation, target);
            var context = new FetchContext(processedSchema, virtualTarget, localObject, true);
            var refContext = virtualTarget.createsDataFetcher() ? context.nextContext(virtualTarget) : context;
            var where = formatWhereContents(context, target.createsDataFetcher());
            var countForImplementation = CodeBlock.builder()
                    .add("$T.select(", DSL.className)
                    .addIf(!isRoot(), () -> CodeBlock.of("$L)", resolverKeyAsTableRecord(context)))
                    .addIf(isRoot(), "$T.count().as($S))", DSL.className, COUNT_FIELD_NAME)
                    .add("\n.from($L)\n", context.getTargetAlias())
                    .add(createSelectJoins(refContext.getJoinSet()))
                    .add(where)
                    .add(createSelectConditions(context.getConditionList(), !where.isEmpty()));

            aliasSet.addAll(refContext.getAliasSet());
            codeForImplementations.declare(getCountVariableName(implementation.getName()), countForImplementation.build());
        });

        var code = CodeBlock.builder()
                .add(createAliasDeclarations(aliasSet))
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
                .addIf(!isRoot(),() -> CodeBlock.of("$N.field(0), $T.count())", UNION_COUNT_QUERY, DSL.className))
                .addIf(isRoot(), "$T.sum($N.field($S, $T.class)))", DSL.className, UNION_COUNT_QUERY, COUNT_FIELD_NAME, INTEGER.className)
                .add("\n.from($N)", UNION_COUNT_QUERY)
                .addStatementIf(isRoot(), "\n.fetchOne(0, $T.class)", INTEGER.className)
                .addIf(!isRoot(), "\n.groupBy($N.field(0))", UNION_COUNT_QUERY)
                .addStatementIf(
                        !isRoot() && resolverKey.isPresent(),
                        () -> CodeBlock.of(
                                "\n.fetchMap($1L -> (($2T) $1N.value1()), $3T::value2)",
                                VAR_RECORD_ITERATOR,
                                resolverKey.get().getTypeName(),
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
                isRoot() ? INTEGER.className : wrapMap(getKeyTableRecordTypeName(referenceField, processedSchema), INTEGER.className)
        )
                .addParameterIf(!isRoot(), () -> wrapSet(getKeyTableRecordTypeName(referenceField, processedSchema)), resolverKeyParamName)
                .addParameters(parser.getMethodParameterSpecs(false, false, true));
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(ObjectField::hasRequiredPaginationFields)
                .filter(it -> it.hasTotalCountFieldInReturnType(processedSchema))
                .filter(it -> !it.hasServiceReference())
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}
