package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Stream;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.indentIfMultiline;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.ORDER_FIELDS_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.tableHasPrimaryKey;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITIES_FIELD;

/**
 * Generator that creates the default data fetching methods
 */
public class FetchMappedObjectDBMethodGenerator extends FetchDBMethodGenerator {

    public FetchMappedObjectDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    /**
     * @param targetField A {@link ObjectField} for which a method should be generated for.
     *                       This must reference an object with the
     *                       "{@link GenerationDirective#TABLE table}" directive set.
     * @return The complete javapoet {@link MethodSpec} based on the provided reference field.
     */
    @Override
    public MethodSpec generate(ObjectField targetField) {
        var targetOwner = getLocalObject();
        var context = new FetchContext(processedSchema, targetField, targetOwner, false);

        // Note that this must happen before alias declaration.
        var selectRowBlock = getSelectRowOrField(targetField, context);
        var querySource = context.renderQuerySource(getSourceTable(context));
        var whereBlock = formatWhereContents(
                context,
                resolverKeyParamName,
                isRoot,
                !isIterableWrappedResolverWithPagination(targetField)
        );

        for (var alias: context.getAliasSet()) {
            if (alias.hasTableMethod()){
                createServiceDependency(alias.getReferenceObjectField());
            }
        }

        var refContext = isIterableWrappedResolverWithPagination(targetField)
                         ? context.nextContext(targetField)
                         : context;
        var actualRefTable = refContext.getTargetAlias();
        var actualRefTableName = refContext.getTargetTableName();
        var selectAliasesBlock = createAliasDeclarations(context.getAliasSet());

        var orderFields = !LookupHelpers.lookupExists(targetField, processedSchema) && (targetField.isIterableWrapped() || targetField.hasForwardPagination() || !isRoot)
                ? createOrderFieldsDeclarationBlock(targetField, actualRefTable, actualRefTableName)
                : CodeBlock.empty();

        var returnType = processedSchema.isRecordType(targetField)
                ? processedSchema.getRecordType(targetField).getGraphClassName()
                : inferFieldTypeName(context.getReferenceObjectField(), true);

        return getSpecBuilder(targetField, returnType, new InputParser(targetField, processedSchema))
                .addCode(declareAllServiceClassesInAliasSet(context.getAliasSet()))
                .addCode(selectAliasesBlock)
                .addCode(orderFields)
                .addCode("return $N\n", VariableNames.CONTEXT_NAME)
                .indent()
                .indent()
                .addCode(".select($L)\n", createSelectBlock(targetField, context, actualRefTable, selectRowBlock))
                .addCodeIf(!querySource.isEmpty() && (context.hasNonSubqueryFields() || context.hasApplicableTable()), ".from($L)\n", querySource)
                .addCode(createSelectJoins(context.getJoinSet()))
                .addCode(whereBlock)
                .addCode(createSelectConditions(context.getConditionList(), !whereBlock.isEmpty()))
                .addCodeIf(!targetField.isResolver() && !orderFields.isEmpty(), ".orderBy($L)\n", ORDER_FIELDS_NAME)
                .addCodeIf(targetField.hasForwardPagination() && !targetField.isResolver(), this::createSeekAndLimitBlock)
                .addCode(setFetch(targetField))
                .unindent()
                .unindent()
                .build();
    }

    private CodeBlock getSelectRowOrField(ObjectField target, FetchContext context) {
        if (!processedSchema.isRecordType(target)) {
            return generateForField(target, context);
        }
        return target.isResolver() && processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(target.getContainerTypeName())
                ? generateCorrelatedSubquery(target, context.nextContext(target))
                : generateSelectRow(context);
    }

    private CodeBlock createSelectBlock(
            ObjectField target,
            FetchContext context,
            String actualRefTable,
            CodeBlock selectRowBlock
    ) {
        return indentIfMultiline(
                Stream.of(
                        getInitialKey(context),
                        CodeBlock.ofIf(
                                target.hasForwardPagination() && !target.isResolver(),
                                "$T.getOrderByToken($L, $L),\n",
                                QUERY_HELPER.className,
                                actualRefTable, ORDER_FIELDS_NAME),
                        selectRowBlock
                ).collect(CodeBlock.joining())
        );
    }

    private CodeBlock setFetch(ObjectField referenceField) {
        var refObject = processedSchema.getObjectOrConnectionNode(referenceField);
        if (refObject == null) {
            return CodeBlock.statementOf(".fetch$L(it -> it.into($T.class))", referenceField.isIterableWrapped() ? "" : "One", referenceField.getTypeClass());
        }

        if (referenceField.hasForwardPagination()) {
            return referenceField.isResolver() ? getPaginationFetchBlockWhenResolver() : getPaginationFetchBlock();
        }

        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);
        if (isRoot && !lookupExists) {
            return CodeBlock.statementOf(".fetch$L(it -> it.into($T.class))", referenceField.isIterableWrapped() ? "" : "One", refObject.getGraphClassName());
        }

        if (referenceField.isResolver() && referenceField.isIterableWrapped()) {
            return CodeBlock
                    .builder()
                    .add(".fetchGroups")
                    .addStatement("($T::value1, $T::value2)", RECORD2.className, RECORD2.className)
                    .build();
        }

        var code = CodeBlock.builder()
                .add(".fetchMap(")
                .addIf(lookupExists, "$T::value1, ", RECORD2.className)
                .addIf(!lookupExists, "r -> r.value1().valuesRow(), ");

        if (processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(referenceField.getContainerTypeName()) && referenceField.isIterableWrapped() && !lookupExists || referenceField.hasForwardPagination()) {
            if (referenceField.hasForwardPagination() && (referenceField.getOrderField().isPresent() || tableHasPrimaryKey(refObject.getTable().getName()))) {
                return code.addStatement("r -> r.value2().map($T::value2))", RECORD2.className).build();
            }

            return code.addStatement("r -> r.value2().map($T::value1))", RECORD1.className).build();
        }

        return code.addStatement("$T::value2)", RECORD2.className).build();
    }

    private CodeBlock getPaginationFetchBlock() {
        var code = CodeBlock.builder();

        if (isRoot) {
            code.add(".fetch()\n");
            code.addStatement(".map(it -> new $T<>(it.value1(), it.value2()))", IMMUTABLE_PAIR.className);
        } else {
            code
                    .add(".fetchMap(\n")
                    .indent()
                    .add("r -> r.value1().valuesRow(),\n")
                    .add("it ->  it.value2().map(r -> r.value2() == null ? null : new $T<>(r.value1(), r.value2()))", IMMUTABLE_PAIR.className)
                    .unindent()
                    .addStatement(")");
        }
        return code.build();
    }

    private CodeBlock getPaginationFetchBlockWhenResolver() {
        return CodeBlock
                .builder()
                .add(".fetchGroups(")
                .add("$T::value1)\n", RECORD3.className)
                .add(".entrySet()\n.stream()\n")
                .add(".collect($T.toMap(\n", COLLECTORS.className)
                .indent()
                .add("$T.Entry::getKey,\n", MAP.className)
                .add("list -> list.getValue().stream()\n")
                .indent()
                .add(".map(e -> new $T<>(e.value2(), e.value3()))\n", IMMUTABLE_PAIR.className)
                .add(".collect($T.toList())\n", COLLECTORS.className)
                .unindent()
                .add(")\n")
                .unindent()
                .addStatement(")")
                .build();
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
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .toList();
    }

    private boolean isIterableWrappedResolverWithPagination(ObjectField field) {
        return field.isResolver() &&
               field.isIterableWrapped() &&
               field.hasForwardPagination();
    }

    private JOOQMapping getSourceTable(FetchContext context) {
       if (context.getReferenceObjectField().isResolver()) {
           return context.getCurrentJoinSequence().getFirst().getTable();
       }

       return getLocalTable();
    }
}
