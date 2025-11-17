package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.mapping.AliasWrapper;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.codebuilding.VariablePrefix;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.GeneratorConfig.useJdbcBatchingForDeletes;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.indentIfMultiline;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.javapoet.CodeBlock.empty;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.tableHasPrimaryKey;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITIES_FIELD;

/**
 * Generator that creates the default data fetching methods
 */
public class FetchMappedObjectDBMethodGenerator extends NestedFetchDBMethodGenerator {

    public FetchMappedObjectDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
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
        var localObject = getLocalObject();
        var context = new FetchContext(processedSchema, target, localObject, false);

        // Note that this must happen before alias declaration.
        var selectRowBlock = getSelectRowOrField(target, context);
        var whereBlock = formatWhereContents(context, resolverKeyParamName, isRoot, target.isResolver());
        for (var alias: context.getAliasSet()) {
            if (alias.hasTableMethod()){
                createServiceDependency(alias.getReferenceObjectField());
            }
        }
        var querySource = context.renderQuerySource(getLocalTable());
        var refContext = target.isResolver() ? context.nextContext(target) : context;
        var actualRefTable = refContext.getTargetAlias();
        var actualRefTableName = refContext.getTargetTableName();
        var selectAliasesBlock = createAliasDeclarations(context.getAliasSet());
        var orderFields = !LookupHelpers.lookupExists(target, processedSchema) && (target.isIterableWrapped() || target.hasForwardPagination() || !isRoot)
                ? createOrderFieldsDeclarationBlock(target, actualRefTable, actualRefTableName)
                : empty();

        var returnType = processedSchema.isRecordType(target)
                ? processedSchema.getRecordType(target).getGraphClassName()
                : inferFieldTypeName(context.getReferenceObjectField(), true);

        // For record types that are NOT reference resolvers, extract the row mapping into a helper method
        var isReferenceResolverField = processedSchema.isReferenceResolverField(target);
        var selectBlock = processedSchema.isRecordType(target) && !isReferenceResolverField
                ? createSelectBlockWithHelperMethod(target, context, actualRefTable)
                : createSelectBlock(target, context, actualRefTable, selectRowBlock);

        return getSpecBuilder(target, returnType, new InputParser(target, processedSchema))
                .addCode(declareAllServiceClassesInAliasSet(context.getAliasSet()))
                .addCode(selectAliasesBlock)
                .addCode(orderFields)
                .addCode("return $N\n", VariableNames.VAR_CONTEXT)
                .indent()
                .indent()
                .addCode(".select($L)\n", selectBlock)
                .addCodeIf(!querySource.isEmpty() && (context.hasNonSubqueryFields() || context.hasApplicableTable()), ".from($L)\n", querySource)
                .addCode(createSelectJoins(context.getJoinSet()))
                .addCode(whereBlock)
                .addCode(createSelectConditions(context.getConditionList(), !whereBlock.isEmpty()))
                .addCodeIf(!target.isResolver() && !orderFields.isEmpty(), ".orderBy($L)\n", VAR_ORDER_FIELDS)
                .addCodeIf(target.hasForwardPagination() && !target.isResolver(), this::createSeekAndLimitBlock)
                .addCode(setFetch(target))
                .unindent()
                .unindent()
                .build();
    }

    private CodeBlock getSelectRowOrField(ObjectField target, FetchContext context) {
        if (!processedSchema.isRecordType(target)) {
            return generateForField(target, context);
        }
        return processedSchema.isReferenceResolverField(target)
                ? generateCorrelatedSubquery(target, context.nextContext(target))
                : generateSelectRow(context);
    }

    private CodeBlock createSelectBlock(ObjectField target, FetchContext context, String actualRefTable, CodeBlock selectRowBlock) {
        return indentIfMultiline(
                Stream.of(
                        getInitialKey(context),
                        CodeBlock.ofIf(target.hasForwardPagination() && !target.isResolver(), "$T.getOrderByToken($L, $L),\n", QUERY_HELPER.className, actualRefTable, VAR_ORDER_FIELDS),
                        selectRowBlock
                ).collect(CodeBlock.joining())
        );
    }

    private CodeBlock createSelectBlockWithHelperMethod(ObjectField target, FetchContext context, String actualRefTable) {
        var helperMethodName = generateHelperMethodName(target);
        var parser = new InputParser(target, processedSchema);
        var allParameters = new ArrayList<>(parser.getMethodInputsWithOrderField().keySet());
        allParameters.addAll(getExtraParameters(target));
        var parameterList = String.join(", ", allParameters);
        var helperCall = CodeBlock.of("$L($L)", helperMethodName, parameterList);
        return createSelectBlock(target, context, actualRefTable, helperCall);
    }

    @Override
    protected CodeBlock getHelperMethodCallForNestedField(ObjectField field, FetchContext context) {
        String helperMethodName;
        if (helperContext != null) {
            // When calling from within a helper at depth N, the nested helper is at depth N+1
            var nestedDepth = helperContext.depth + 1;
            var baseName = NestedFetchDBMethodGenerator.generateDepthBasedMethodName(
                    helperContext.helperMethodName, nestedDepth, field.getName());
            helperMethodName = helperContext.getNextCallName(baseName);
        } else {
            helperMethodName = generateHelperMethodName(field);
        }

        var parameters = new ArrayList<String>();

        if (methodState != null && methodState.rootFetchContext != null) {
            var tableMethodInputs = collectTableMethodInputNames(methodState.rootFetchContext.getAliasSet());
            parameters.addAll(tableMethodInputs);
        }

        parameters.addAll(getExtraParameters(field));
        return CodeBlock.of("$L($L)", helperMethodName, String.join(", ", parameters));
    }

    private ArrayList<String> getExtraParameters(ObjectField field) {
        var parameters = new ArrayList<String>();

        if (GeneratorConfig.shouldMakeNodeStrategy()) {
            parameters.add(VAR_NODE_STRATEGY);
        }

        var contextFields = processedSchema.getAllContextFields(field).keySet().stream()
                .map(VariablePrefix::contextFieldPrefix)
                .toList();
        parameters.addAll(contextFields);
        return parameters;
    }

    private CodeBlock setFetch(ObjectField referenceField) {
        var refObject = processedSchema.getObjectOrConnectionNode(referenceField);
        if (refObject == null) {
            return CodeBlock.statementOf(
                    ".fetch$L($L -> $N.into($T.class))",
                    referenceField.isIterableWrapped() ? "" : "One",
                    VAR_ITERATOR,
                    VAR_ITERATOR,
                    referenceField.getTypeClass()
            );
        }

        if (referenceField.hasForwardPagination()) {
            return getPaginationFetchBlock();
        }

        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);
        if (isRoot && !lookupExists) {
            return CodeBlock.statementOf(
                    ".fetch$L($L -> $N.into($T.class))",
                    referenceField.isIterableWrapped() ? "" : "One",
                    VAR_ITERATOR,
                    VAR_ITERATOR,
                    refObject.getGraphClassName()
            );
        }

        var code = CodeBlock.builder()
                .add(".fetchMap(")
                .addIf(lookupExists, "$T::value1, ", RECORD2.className)
                .addIf(!lookupExists, "$1L -> $1N.value1().valuesRow(), ", VAR_RECORD_ITERATOR);

        if (processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(referenceField.getContainerTypeName()) && referenceField.isIterableWrapped() && !lookupExists || referenceField.hasForwardPagination()) {
            if (referenceField.hasForwardPagination() && (referenceField.getOrderField().isPresent() || tableHasPrimaryKey(refObject.getTable().getName()))) {
                return code.addStatement("$1L -> $1N.value2().map($2T::value2))", VAR_RECORD_ITERATOR, RECORD2.className).build();
            }

            return code.addStatement("$1L -> $1N.value2().map($2T::value1))", VAR_RECORD_ITERATOR, RECORD1.className).build();
        }

        return code.addStatement("$T::value2)", RECORD2.className).build();
    }

    private CodeBlock getPaginationFetchBlock() {
        var code = CodeBlock.builder();

        if (isRoot) {
            code
                    .add(".fetch()\n")
                    .addStatement(".map($1L -> new $2T<>($1N.value1(), $1N.value2()))", VAR_ITERATOR, IMMUTABLE_PAIR.className);
        } else {
            code
                    .add(".fetchMap(\n")
                    .indent()
                    .add("$1L -> $1N.value1().valuesRow(),\n", VAR_RECORD_ITERATOR)
                    .add(
                            "$1L -> $1N.value2().map($2L -> $2N.value2() == null ? null : new $3T<>($2N.value1(), $2N.value2()))",
                            VAR_ITERATOR,
                            VAR_RECORD_ITERATOR,
                            IMMUTABLE_PAIR.className
                    )
                    .unindent()
                    .addStatement(")");
        }
        return code.build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        var mainMethods = getLocalObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it) && !processedSchema.isUnion(it))
                .filter(it -> !it.getName().equals(FEDERATION_ENTITIES_FIELD.getName()))
                .filter(it -> !processedSchema.isFederationService(it))
                .filter(GenerationSourceField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .filter(it -> !(it.isDeleteMutation() && !useJdbcBatchingForDeletes()))
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .toList();

        var topLevelFields = getLocalObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it) && !processedSchema.isUnion(it))
                .filter(it -> !it.getName().equals(FEDERATION_ENTITIES_FIELD.getName()))
                .filter(it -> !processedSchema.isFederationService(it))
                .filter(GenerationSourceField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .filter(it -> !(it.isDeleteMutation() && !useJdbcBatchingForDeletes()))
                .filter(processedSchema::isRecordType)
                .toList();

        var helperMethods = new ArrayList<MethodSpec>();

        // Generate helper methods for top-level fields
        for (var field : topLevelFields) {
            // Create per-method state for this top-level field and all its nested helpers
            var rootContext = new FetchContext(processedSchema, field, getLocalObject(), false);
            methodState = new NestedFetchDBMethodGenerator.MethodGenerationState(field, rootContext);

            helperMethods.add(generateHelperMethod(field));
            helperMethods.addAll(generateNestedHelperMethods(field));

            // Clear per-method state before moving to next field
            methodState = null;
        }

        var allMethods = new ArrayList<MethodSpec>();
        allMethods.addAll(mainMethods);
        allMethods.addAll(helperMethods);
        return allMethods;
    }

    private Set<String> collectTableMethodInputNames(Set<AliasWrapper> aliasSet) {
        return aliasSet.stream()
                .filter(aliasWrapper -> aliasWrapper.hasTableMethod() && !aliasWrapper.getInputNames().isEmpty())
                .flatMap(aliasWrapper -> aliasWrapper.getInputNames().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
