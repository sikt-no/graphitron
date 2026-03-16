package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.naming.GraphQLReservedName;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.GeneratorConfig.optionalSelectIsEnabled;
import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.indentIfMultiline;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.inferFieldTypeName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.javapoet.CodeBlock.empty;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.tableHasPrimaryKey;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITIES_FIELD;

/**
 * Generator that creates the default data fetching methods
 */
public class FetchMappedObjectDBMethodGenerator extends FetchDBMethodGenerator {

    public FetchMappedObjectDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(
                localObject,
                processedSchema,
                localObject.isOperationRoot()
                && localObject.getName().equals(GraphQLReservedName.SCHEMA_MUTATION.getName())
        );
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
        var whereBlock = formatWhereContents(context, target.createsDataFetcher());
        for (var alias: context.getAliasSet()) {
            if (alias.hasTableMethod()){
                createServiceDependency(alias.getReferenceObjectField());
            }
        }
        var querySource = context.renderQuerySource(getLocalTable());
        var refContext = target.createsDataFetcher() ? context.nextContext(target) : context;
        var actualRefTable = refContext.getTargetAlias();
        var actualRefTableName = refContext.getTargetTableName();
        var selectAliasesBlock = createAliasDeclarations(context.getAliasSet());
        var orderFields = !LookupHelpers.lookupExists(target, processedSchema) && (target.isIterableWrapped() || target.hasForwardPagination() || !isRoot())
                ? createOrderFieldsDeclarationBlock(target, actualRefTable, actualRefTableName)
                : empty();

        var returnType = processedSchema.isRecordType(target)
                ? processedSchema.getRecordType(target).getGraphClassName()
                : inferFieldTypeName(context.getReferenceObjectField(), true, processedSchema);

        var isReferenceResolverField = processedSchema.isReferenceResolverField(target);
        var parser = new InputParser(target, processedSchema);
        var selectBlockToUse = getSelectBlockForRecord(target, selectRowBlock, isReferenceResolverField, parser);
        var selectBlock = createSelectBlock(target, context, actualRefTable, selectBlockToUse);

        return getSpecBuilder(target, returnType, parser)
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
                .addCodeIf(!target.createsDataFetcher() && !orderFields.isEmpty(), ".orderBy($L)\n", VAR_ORDER_FIELDS)
                .addCodeIf(target.hasForwardPagination() && !target.createsDataFetcher(), this::createSeekAndLimitBlock)
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
                ? generateCorrelatedSubquery(context.nextContext(target))
                : generateSelectRow(context);
    }

    /**
     * Determine the select block to use for record types. By default, delegates to a helper method
     * unless the field is a reference resolver field (which uses a correlated subquery).
     * Subclasses can override to always inline the select.
     */
    protected CodeBlock getSelectBlockForRecord(ObjectField target, CodeBlock selectRowBlock, boolean isReferenceResolverField, InputParser parser) {
        if (!processedSchema.isRecordType(target) || isReferenceResolverField) {
            return selectRowBlock;
        }
        var methodInputs = parser.getMethodInputNames(true, false, true);
        if (optionalSelectIsEnabled()) methodInputs.add(VAR_SELECT);
        if (shouldMakeNodeStrategy()) methodInputs.add(0, VAR_NODE_STRATEGY);
        return CodeBlock.of("$L($L)", generateHelperMethodName(target), methodInputs.stream().map(CodeBlock::of).collect(CodeBlock.joining(", ")));
    }

    private CodeBlock createSelectBlock(ObjectField target, FetchContext context, String actualRefTable, CodeBlock selectRowBlock) {
        return indentIfMultiline(
                Stream.of(
                        getInitialKey(context),
                        CodeBlock.ofIf(target.hasForwardPagination() && !target.createsDataFetcher(), "$T.getOrderByToken($L, $L),\n", QUERY_HELPER.className, actualRefTable, VAR_ORDER_FIELDS),
                        selectRowBlock
                ).collect(CodeBlock.joining())
        );
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
        if (isRoot() && !lookupExists) {
            return CodeBlock.statementOf(
                    ".fetch$L($L -> $N.into($T.class))",
                    referenceField.isIterableWrapped() ? "" : "One",
                    VAR_ITERATOR,
                    VAR_ITERATOR,
                    refObject.getGraphClassName()
            );
        }

        var code = CodeBlock.builder()
                .add(".fetchMap($T::value1, ", RECORD2.className);

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

        if (isRoot()) {
            code
                    .add(".fetch()\n")
                    .addStatement(".map($1L -> new $2T<>($1N.value1(), $1N.value2()))", VAR_ITERATOR, IMMUTABLE_PAIR.className);
        } else {
            code
                    .add(".fetchMap(\n")
                    .indent()
                    .add("$T::value1,\n", RECORD2.className)
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
        return getLocalObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it) && !processedSchema.isUnion(it))
                .filter(it -> !it.getName().equals(FEDERATION_ENTITIES_FIELD.getName()))
                .filter(it -> !processedSchema.isFederationService(it))
                .filter(GenerationSourceField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .filter(it -> !processedSchema.isDeleteMutationWithReturning(it))
                .filter(it -> !processedSchema.isInsertMutationWithReturning(it))
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .toList();
    }
}
