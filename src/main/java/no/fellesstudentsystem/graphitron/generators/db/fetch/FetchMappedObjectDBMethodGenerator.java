package no.fellesstudentsystem.graphitron.generators.db.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.helpers.queries.LookupHelpers;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.PAGE_SIZE_NAME;
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
        var selectCode = generateSelectRow(context);
        var where = formatWhereContents(context);

        var code = CodeBlock
                .builder()
                .add(declareAliasesAndSetInitialCode(context))
                .add(selectCode)
                .add(".as($S)\n", target.getName())
                .unindent()
                .unindent()
                .add(")\n")
                .add(".from($L)\n", context.renderQuerySource(getLocalTable()))
                .add(createSelectJoins(context))
                .add(where)
                .add(createSelectConditions(context))
                .add(setPaginationAndFetch(target, context.getReferenceTable().getMappingName()));

        return getSpecBuilder(target, context.getReferenceObject().getGraphClassName())
                .addCode(code.build())
                .build();
    }

    private CodeBlock declareAliasesAndSetInitialCode(FetchContext context) {
        var code = CodeBlock
                .builder()
                .add(createSelectAliases(context.getJoinSet()))
                .add("return $N\n", VariableNames.CONTEXT_NAME)
                .indent()
                .indent()
                .add(".select(\n")
                .indent()
                .indent();

        var ref = context.getReferenceObjectField();
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
    private MethodSpec.Builder getSpecBuilder(ObjectField referenceField, TypeName refTypeName) {
        var spec = getDefaultSpecBuilder(
                asQueryMethodName(referenceField.getName(), getLocalObject().getName()),
                getReturnType(referenceField, refTypeName)
        );
        if (!isRoot) {
            spec.addParameter(getStringSetTypeName(), idParamName);
        }

        referenceField
                .getNonReservedArgumentsWithOrderField()
                .forEach(i -> spec.addParameter(inputIterableWrap(i), i.getName()));

        if (referenceField.hasForwardPagination()) {
            spec.addParameter(INTEGER.className, PAGE_SIZE_NAME);
            spec.addParameter(STRING.className, GraphQLReservedName.PAGINATION_AFTER.getName());
        }
        return spec.addParameter(SELECTION_SET.className, SELECTION_NAME);
    }

    @NotNull
    private TypeName getReturnType(ObjectField referenceField, TypeName refClassName) {
        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);
        if (isRoot && !lookupExists) {
            return wrapList(refClassName);
        } else {
            return wrapMap(STRING.className, wrapListIf(refClassName, referenceField.isIterableWrapped() && !lookupExists || referenceField.hasForwardPagination()));
        }
    }

    private CodeBlock setPaginationAndFetch(ObjectField referenceField, String actualRefTable) {
        var refObject = processedSchema.getObjectOrConnectionNode(referenceField);
        var refTable = refObject.getTable().getMappingName();
        var code = CodeBlock.builder();
        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);

        if (!lookupExists && isRoot || referenceField.hasForwardPagination()) {

            var orderByField = referenceField.getOrderField();
            orderByField.ifPresentOrElse(
                    it -> code.add(createCustomOrderBy(it, actualRefTable)),
                    () -> code.add(".orderBy($N.getIdFields())\n", actualRefTable));

            if (referenceField.hasForwardPagination()) {
                orderByField.ifPresentOrElse(
                        it -> code.add(".seek(\n")
                                .indent()
                                .add("$N == null\n", it.getName())
                                .indent()
                                .add("? $N.getIdValues($N)\n", refTable, GraphQLReservedName.PAGINATION_AFTER.getName())
                                .add(": $N == null ? new $T[]{} : $N.split($S))\n",
                                        GraphQLReservedName.PAGINATION_AFTER.getName(), OBJECT.className, GraphQLReservedName.PAGINATION_AFTER.getName(), ",")
                                .unindent().unindent(),
                        () -> code.add(".seek($N.getIdValues($N))\n", refTable, GraphQLReservedName.PAGINATION_AFTER.getName()));
                code.add(".limit($N + 1)\n", PAGE_SIZE_NAME);
            }
        }

        if (isRoot && !lookupExists) {
            code.addStatement(".fetch(0, $T.class)", refObject.getGraphClassName());
        } else {
            code
                    .add(".")
                    .add(
                            referenceField.isIterableWrapped() && !lookupExists || referenceField.hasForwardPagination()
                                    ? "fetchGroups"
                                    : "fetchMap"
                    )
                    .addStatement("($T::value1, $T::value2)", RECORD2.className, RECORD2.className);
        }
        return code.unindent().unindent().build();
    }

    @NotNull
    private CodeBlock createCustomOrderBy(InputField orderInputField, String actualRefTable) {
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
                .add(".orderBy(\n")
                .indent()
                .add("$N == null\n", orderInputFieldName)
                .indent().indent()
                .add("? $N.getIdFields()\n", actualRefTable)
                .add(": $T.getSortFields($N.getIndexes(), $L.get($N.get$L().toString()), $N.getDirection().toString()))\n",
                        QUERY_HELPER.className, actualRefTable, sortFieldsMapBlock, orderInputFieldName, capitalize(GraphQLReservedName.ORDER_BY_FIELD.getName()), orderInputFieldName)
                .unindent().unindent().unindent()
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getReferredFieldsFromObjectNames(processedSchema.getNamesWithTableOrConnections())
                .stream()
                .filter(ObjectField::isGenerated)
                .filter(it -> !processedSchema.isInterface(it))
                .map(this::generate)
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject().getFields().stream();
        return isRoot
                ? fieldStream.allMatch(ObjectField::isGenerated)
                : fieldStream.allMatch(f -> !f.isResolver() || f.isGenerated());
    }
}