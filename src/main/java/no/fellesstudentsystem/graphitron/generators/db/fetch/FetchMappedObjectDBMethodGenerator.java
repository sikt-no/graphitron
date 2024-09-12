package no.fellesstudentsystem.graphitron.generators.db.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphitron.generators.context.InputParser;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.helpers.queries.LookupHelpers;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.capitalize;

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
        var context = new FetchContext(processedSchema, target, localObject);

        // Note that this must happen before alias declaration.
        var selectRowBlock = generateSelectRow(context);
        var whereBlock = formatWhereContents(context);

        var actualRefTable = context.getCurrentJoinSequence().render().toString();

        var selectAliasesBlock = createSelectAliases(context.getJoinSet());

        Optional<CodeBlock> maybeOrderFields = !LookupHelpers.lookupExists(target, processedSchema) && (target.isIterableWrapped() || target.hasForwardPagination() || !isRoot)
                ? Optional.of(createOrderFieldsDeclarationBlock(target, actualRefTable))
                : Optional.empty();

        var code = CodeBlock
                .builder()
                .add(selectAliasesBlock)
                .add(maybeOrderFields.orElse(empty()))
                .add("return $N\n", VariableNames.CONTEXT_NAME)
                .indent()
                .indent()
                .add(".select(")
                .add(createSelectBlock(target, context, actualRefTable, selectRowBlock))
                .add(")\n.from($L)\n", context.renderQuerySource(getLocalTable()))
                .add(createSelectJoins(context.getJoinSet()))
                .add(whereBlock)
                .add(createSelectConditions(context.getConditionList()))
                .add(maybeOrderFields
                        .map(it -> CodeBlock.of(".orderBy($L)\n", ORDER_FIELDS_NAME))
                        .orElse(empty()))
                .add(target.hasForwardPagination()
                        ? createSeekAndLimitBlock(target)
                        : empty())
                .add(setFetch(target));

        var parser = new InputParser(target, processedSchema);
        return getSpecBuilder(target, context.getReferenceObject().getGraphClassName(), parser)
                .addCode(code.build())
                .build();
    }

    private CodeBlock createSelectBlock(ObjectField target, FetchContext context, String actualRefTable, CodeBlock selectRowBlock) {
        return indentIfMultiline(
                Stream.of(
                                getInitialID(context),
                                target.hasForwardPagination() ? CodeBlock.of("$T.getOrderByToken($L, $L),\n", QUERY_HELPER.className, actualRefTable, ORDER_FIELDS_NAME) : empty(),
                                selectRowBlock
                        ).collect(CodeBlock.joining(""))
        );
    }

    private CodeBlock getInitialID(FetchContext context) {
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
            code.add("$L.getId(),\n", table);
        }
        return code.build();
    }

    @NotNull
    private MethodSpec.Builder getSpecBuilder(ObjectField referenceField, TypeName refTypeName, InputParser parser) {
        var spec = getDefaultSpecBuilder(
                asQueryMethodName(referenceField.getName(), getLocalObject().getName()),
                getReturnType(referenceField, refTypeName)
        );
        if (!isRoot) {
            spec.addParameter(getStringSetTypeName(), idParamName);
        }

        parser.getMethodInputsWithOrderField().forEach((key, value) -> spec.addParameter(iterableWrapType(value), key));

        if (referenceField.hasForwardPagination()) {
            spec
                    .addParameter(INTEGER.className, PAGE_SIZE_NAME)
                    .addParameter(STRING.className, GraphQLReservedName.PAGINATION_AFTER.getName());
        }
        return spec.addParameter(SELECTION_SET.className, VARIABLE_SELECT);
    }

    @NotNull
    private TypeName getReturnType(ObjectField referenceField, TypeName refClassName) {
        TypeName type = referenceField.hasForwardPagination() ? ParameterizedTypeName.get(PAIR.className, STRING.className, refClassName) : refClassName;

        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);

        if (isRoot && !lookupExists) {
            return wrapListIf(type, referenceField.isIterableWrapped() || referenceField.hasForwardPagination());
        } else {
            return wrapMap(STRING.className, wrapListIf(type, referenceField.isIterableWrapped() && !lookupExists || referenceField.hasForwardPagination()));
        }
    }

    private CodeBlock createOrderFieldsDeclarationBlock(ObjectField referenceField, String actualRefTable) {
        var orderByField = referenceField.getOrderField();
        var primaryKeyFieldsBlock = getPrimaryKeyFieldsBlock(actualRefTable);
        var code = CodeBlock.builder();
        orderByField.ifPresentOrElse(
                it -> code.add(createCustomOrderBy(it, actualRefTable, primaryKeyFieldsBlock)),
                () -> code.add("$L", primaryKeyFieldsBlock));
        return declare(ORDER_FIELDS_NAME, code.build());
    }

    private CodeBlock createSeekAndLimitBlock(ObjectField referenceField) {
        var code = CodeBlock.builder()
                .add(".seek($T.getOrderByValues($N, $L, $N))\n", QUERY_HELPER.className, CONTEXT_NAME, ORDER_FIELDS_NAME, GraphQLReservedName.PAGINATION_AFTER.getName());

        if (referenceField.isResolver()) {
            code.add(".limit($N * $N.size() + 1)\n", PAGE_SIZE_NAME, idParamName);
        } else {
            code.add(".limit($N + 1)\n", PAGE_SIZE_NAME);
        }
        return code.build();
    }

    private static CodeBlock getPrimaryKeyFieldsBlock(String actualRefTable) {
        return CodeBlock.of("$N.fields($N.getPrimaryKey().getFieldsArray())", actualRefTable, actualRefTable);
    }

    private CodeBlock setFetch(ObjectField referenceField) {
        var refObject = processedSchema.getObjectOrConnectionNode(referenceField);
        if (!refObject.hasTable()) {
            return empty();
        }

        if (referenceField.hasForwardPagination()) {
            return getPaginationFetchBlock();
        }

        var code = CodeBlock.builder();

        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);
        if (isRoot && !lookupExists) {
            code.addStatement(".fetch$L(it -> it.into($T.class))", referenceField.isIterableWrapped() ? "" : "One", refObject.getGraphClassName());
        } else {
            code
                    .add(".")
                    .add(
                            referenceField.isIterableWrapped() && !lookupExists
                                    ? "fetchGroups"
                                    : "fetchMap"
                    )
                    .addStatement("($T::value1, $T::value2)", RECORD2.className, RECORD2.className);
        }
        return code.unindent().unindent().build();
    }

    private CodeBlock getPaginationFetchBlock() {
        var code = CodeBlock.builder();

        if (isRoot) {
            code.add(".fetch()\n");
            code.addStatement(".map(it -> new $T<>(it.value1(), it.value2()))", IMMUTABLE_PAIR.className);
        } else {
            code
                    .add(".fetchGroups(\n")
                    .indent()
                    .add("$T::value1,\n", RECORD3.className)
                    .add("it -> it.value3() == null ? null : new $T<>(it.value2(), it.value3())\n", IMMUTABLE_PAIR.className)
                    .unindent()
                    .addStatement(")");
        }
        return code.unindent().unindent().build();
    }

    @NotNull
    private CodeBlock createCustomOrderBy(InputField orderInputField, String actualRefTable, CodeBlock primaryKeyFieldsBlock) {
        var orderByFieldEnum = processedSchema.getOrderByFieldEnum(orderInputField);
        var orderByFieldToDBIndexName = orderByFieldEnum
                .getFields()
                .stream()
                .collect(Collectors.toMap(AbstractField::getName, enumField -> enumField.getIndexName().orElseThrow()));

        orderByFieldToDBIndexName.forEach((orderByField, indexName) -> Validate.isTrue(TableReflection.tableHasIndex(actualRefTable, indexName),
                "Table '%S' has no index '%S' necessary for sorting by '%s'", actualRefTable, indexName, orderByField));

        var sortFieldMapEntries = orderByFieldToDBIndexName.entrySet()
                .stream()
                .map(it -> CodeBlock.of("Map.entry($S, $S)", it.getKey(), it.getValue()))
                .collect(CodeBlock.joining(",\n"));
        var sortFieldsMapBlock = CodeBlock.builder().add("$T.ofEntries(\n", MAP.className)
                .indent()
                .add("$L)", sortFieldMapEntries)
                .add("\n")
                .unindent()
                .build();

        var orderInputFieldName = orderInputField.getName();
        return CodeBlock.builder()
                .add("$N == null\n", orderInputFieldName)
                .indent().indent()
                .add("? $L\n", primaryKeyFieldsBlock)
                .add(": $T.getSortFields($N.getIndexes(), $L.get($N.get$L().toString()), $N.getDirection().toString())",
                        QUERY_HELPER.className, actualRefTable, sortFieldsMapBlock, orderInputFieldName, capitalize(GraphQLReservedName.ORDER_BY_FIELD.getName()), orderInputFieldName)
                .unindent().unindent()
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return ((ObjectDefinition) getLocalObject())
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it))
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .map(this::generate)
                .filter(it -> !it.code.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it))
                .filter(it -> !it.hasServiceReference());
        return isRoot
                ? fieldStream.allMatch(GenerationField::isGeneratedWithResolver)
                : fieldStream.allMatch(f -> (!f.isResolver() || f.isGeneratedWithResolver()));
    }
}