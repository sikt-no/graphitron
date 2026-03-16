package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.KeyWrapper;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.getSelectKeyColumnRow;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapMap;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapSet;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.*;

/**
 * Generator that creates DB fetch methods for fields where a @service returns a table-backed type.
 * The generated method takes a set of primary keys and batch-fetches all matching records in a
 * single query, returning a Map from key to result. This is used for both single and listed fields;
 * the runtime helper handles wrapping/unwrapping for the single case.
 */
public class ServiceResultFetchDBMethodGenerator extends FetchDBMethodGenerator {

    public ServiceResultFetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var localObject = getLocalObject();
        var context = new FetchContext(processedSchema, target, localObject, false);

        var querySource = context.renderQuerySource(getLocalTable());
        var returnType = processedSchema.getRecordType(target).getGraphClassName();
        var recordType = processedSchema.getRecordType(target);
        var tableName = recordType.getTable().getName();
        var pk = getPrimaryKeyForTable(tableName).orElseThrow();
        var keyRowType = new KeyWrapper(pk).getRowTypeName();
        var alias = context.getTargetAlias();

        // Build select expression inline. Must be called BEFORE createAliasDeclarations
        // since it populates the shared alias set with all nested aliases discovered
        // during recursive context traversal.
        var selectBlock = generateSelectRow(context);
        var selectAliasesBlock = createAliasDeclarations(context.getAliasSet());

        // Build the key column for the SELECT (becomes value1 in the result)
        var keyColumnRow = getSelectKeyColumnRow(pk, tableName, alias);

        var methodName = asQueryMethodName(target.getName(), localObject.getName());

        return getDefaultSpecBuilder(methodName, wrapMap(keyRowType, returnType))
                .addParameter(wrapSet(keyRowType), VAR_RESOLVER_KEYS)
                .addParameter(SELECTION_SET.className, VAR_SELECT)
                .addCode(selectAliasesBlock)
                .addCode("return $N\n", VAR_CONTEXT)
                .indent()
                .indent()
                .addCode(".select(\n")
                .indent()
                .addCode("$L,\n", keyColumnRow)
                .addCode("$L", selectBlock)
                .unindent()
                .addCode("\n)\n")
                .addCodeIf(!querySource.isEmpty() && (context.hasNonSubqueryFields() || context.hasApplicableTable()), ".from($L)\n", querySource)
                .addCode(createSelectJoins(context.getJoinSet()))
                .addCode(".where($L.in($N))\n", keyColumnRow, VAR_RESOLVER_KEYS)
                .addCode(createSelectConditions(context.getConditionList(), true))
                .addStatement(".fetchMap($N -> $N.value1().valuesRow(), $T::value2)", VAR_RECORD_ITERATOR, VAR_RECORD_ITERATOR, RECORD2.className)
                .unindent()
                .unindent()
                .build();
    }

    /**
     * Override to return null so that nested record fields are inlined rather than
     * delegating to helper methods (which are not generated for service-returning-table fields).
     */
    @Override
    protected CodeBlock getHelperMethodCallForNestedField(ObjectField field, FetchContext context) {
        return null;
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .filter(GenerationSourceField::isGeneratedWithResolver)
                .filter(ObjectField::hasServiceReference)
                .filter(it -> !it.hasPagination())
                .filter(it -> !it.createsDataFetcher())
                .filter(processedSchema::hasTableObject)
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .toList();
    }
}
