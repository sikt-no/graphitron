package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualTableRecordField;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DBMethodGenerator;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.inResolverKeysBlock;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_CONTEXT;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.internalPrefix;
import static no.sikt.graphitron.mappings.TableReflection.getRecordClass;
import static no.sikt.graphitron.mappings.TableReflection.getTableJavaFieldNameByTableName;

/**
 * Generator that creates methods for fetching existing table records by primary key.
 * Used in store-based upserts to retrieve current DB state before mutating data.
 */
public class FetchTableRecordDBMethodGenerator extends DBMethodGenerator<VirtualTableRecordField> {

    public static final String PRIMARY_KEYS = "primaryKeys";

    public FetchTableRecordDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    /**
     * @param target A {@link VirtualTableRecordField} identifying the table to fetch records from.
     * @return A method that fetches records from the target table matching the primary keys of the input records.
     */
    @Override
    public MethodSpec generate(VirtualTableRecordField target) {
        var tableJavaName = getTableJavaFieldNameByTableName(target.tableName()).orElse(target.tableName());
        var recordClass = getRecordClass(target.tableName()).orElseThrow();
        var methodName = getMethodName(recordClass.getSimpleName());

        var code = CodeBlock.builder()
                .add("return $N.selectFrom($N)\n", VAR_CONTEXT, tableJavaName)
                .indent()
                .indent()
                .add(".where($L)\n", inResolverKeysBlock(internalPrefix(PRIMARY_KEYS), tableJavaName))
                .addStatement(".fetch()")
                .unindent()
                .unindent()
                .build();


        return getDefaultSpecBuilder(methodName, ParameterizedTypeName.get(List.class, recordClass), true)
                .addParameter(ParameterizedTypeName.get(List.class, recordClass), internalPrefix(PRIMARY_KEYS))
                .addCode(code)
                .build();
    }

    /**
     * @return The generated method name for fetching records of the given type, e.g. {@code "fetchCustomerRecords"}.
     */
    public static String getMethodName(String recordClassName) {
        return "fetch" + recordClassName + "s";
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (GeneratorConfig.generateUpsertAsStore()) {
            Set<String> tablesWithUpsert = localObject.getFields().stream()
                    .filter(ObjectField::hasMutationType)
                    .filter(it -> it.getMutationType().equals(MutationType.UPSERT))
                    .map(processedSchema::findInputTables)
                    .flatMap(Collection::stream)
                    .map(it -> it.getTable().getName())
                    .collect(Collectors.toSet());

            return tablesWithUpsert.stream()
                    .map(it -> generate(new VirtualTableRecordField(it)))
                    .toList();
        }
        return List.of();
    }
}
