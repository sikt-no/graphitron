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
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
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

        var querySource = context.renderQuerySource(getLocalTable());

        var refContext = target.isResolver() ? context.nextContext(target) : context;
        var actualRefTable = refContext.getTargetAlias();
        var actualRefTableName = refContext.getTargetTableName();

        var selectAliasesBlock = createAliasDeclarations(context.getAliasSet());

        Optional<CodeBlock> maybeOrderFields = !LookupHelpers.lookupExists(target, processedSchema) && (target.isIterableWrapped() || target.hasForwardPagination() || !isRoot)
                ? maybeCreateOrderFieldsDeclarationBlock(target, actualRefTable, actualRefTableName)
                : Optional.empty();

        var hasNonSubqueryFields = !processedSchema.isRecordType(target) || processedSchema.getRecordType(target)
                .getFields()
                .stream()
                .anyMatch(it -> !it.invokesSubquery() || processedSchema.isRecordType(it) && processedSchema.getRecordType(it).hasTable() && !processedSchema.getRecordType(it).getTable().equals(context.getTargetTable()));
        var code = CodeBlock
                .builder()
                .add(selectAliasesBlock)
                .add(maybeOrderFields.orElse(CodeBlock.empty()))
                .add("return $N\n", VariableNames.CONTEXT_NAME)
                .indent()
                .indent()
                .add(".select($L)\n", createSelectBlock(target, context, actualRefTable, selectRowBlock))
                .addIf(!querySource.isEmpty() && (hasNonSubqueryFields || context.hasApplicableTable()), ".from($L)\n", querySource)
                .add(createSelectJoins(context.getJoinSet()))
                .add(whereBlock)
                .add(createSelectConditions(context.getConditionList(), !whereBlock.isEmpty()))
                .add(target.isResolver() ? CodeBlock.empty() : maybeOrderFields
                        .map(it -> CodeBlock.of(".orderBy($L)\n", ORDER_FIELDS_NAME))
                        .orElse(CodeBlock.empty()))
                .add(target.hasForwardPagination() && !target.isResolver()
                        ? createSeekAndLimitBlock()
                        : CodeBlock.empty())
                .add(setFetch(target))
                .unindent()
                .unindent();

        var parser = new InputParser(target, processedSchema);
        var returnType = processedSchema.isRecordType(target)
                ? processedSchema.getRecordType(target).getGraphClassName()
                : inferFieldTypeName(context.getReferenceObjectField(), true);
        return getSpecBuilder(target, returnType, parser)
                .addCode(code.build())
                .build();
    }

    private CodeBlock getSelectRowOrField(ObjectField target, FetchContext context) {
        if (!processedSchema.isRecordType(target)) {
            return generateForScalarField(target, context);
        }
        return target.isResolver() ? generateCorrelatedSubquery(target, context.nextContext(target)) : generateSelectRow(context);
    }

    private CodeBlock createSelectBlock(ObjectField target, FetchContext context, String actualRefTable, CodeBlock selectRowBlock) {
        return indentIfMultiline(
                Stream.of(
                        getInitialKey(context),
                        target.hasForwardPagination() && !target.isResolver() ? CodeBlock.of("$T.getOrderByToken($L, $L),\n", QUERY_HELPER.className, actualRefTable, ORDER_FIELDS_NAME) : CodeBlock.empty(),
                        selectRowBlock
                ).collect(CodeBlock.joining())
        );
    }

    private CodeBlock getInitialKey(FetchContext context) {
        var code = CodeBlock.builder();

        var ref = (ObjectField) context.getReferenceObjectField();
        var table = context.renderQuerySource(getLocalTable());
        if (LookupHelpers.lookupExists(ref, processedSchema)) {
            var concatBlock = LookupHelpers.getLookUpKeysAsColumnList(ref, table, processedSchema);
            if (concatBlock.toString().contains(".inline(")) {
                code.add("$T.concat($L),\n", DSL.className, concatBlock);
            } else {
                code.add(concatBlock).add(",\n");
            }
        } else if (!isRoot) {
            code.add("$L,\n", getSelectKeyColumnRow(context));
        }
        return code.build();
    }

    private CodeBlock setFetch(ObjectField referenceField) {
        var refObject = processedSchema.getObjectOrConnectionNode(referenceField);
        if (refObject == null) {
            return CodeBlock.builder().addStatement(".fetch$L(it -> it.into($T.class))", referenceField.isIterableWrapped() ? "" : "One", referenceField.getTypeClass()).build();
        }

        if (referenceField.hasForwardPagination()) {
            return getPaginationFetchBlock();
        }

        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);
        if (isRoot && !lookupExists) {
            return CodeBlock
                    .builder()
                    .addStatement(".fetch$L(it -> it.into($T.class))", referenceField.isIterableWrapped() ? "" : "One", refObject.getGraphClassName())
                    .build();
        }

        var code = CodeBlock.builder()
                .add(".fetchMap(")
                .addIf(lookupExists, "$T::value1, ", RECORD2.className)
                .addIf(!lookupExists, "r -> r.value1().valuesRow(), ");

        if (referenceField.isIterableWrapped() && !lookupExists || referenceField.hasForwardPagination()) {
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
}
