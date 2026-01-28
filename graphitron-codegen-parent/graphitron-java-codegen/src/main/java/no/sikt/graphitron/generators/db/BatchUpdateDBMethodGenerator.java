package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DBMethodGenerator;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_ITERATOR;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.internalPrefix;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

/**
 * Generator that creates the default data mutation methods.
 */
public class BatchUpdateDBMethodGenerator extends DBMethodGenerator<ObjectField> {
    private static final Map<MutationType, String> mutationConverter = Map.of(
            MutationType.UPDATE, "batchUpdate",
            MutationType.DELETE, "batchDelete",
            MutationType.INSERT, "batchInsert",
            MutationType.UPSERT, "batchMerge"
    );

    private static final String VARIABLE_RECORD_LIST = internalPrefix("recordList"), CONFIG = internalPrefix("config");

    public BatchUpdateDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    /**
     * @param target A {@link ObjectField} for which a mutation method should be generated for.
     *                       This must reference a field located within the Mutation type and with the
     *                       "{@link GenerationDirective#MUTATION mutationType}" directive set.
     * @return The complete javapoet {@link MethodSpec} based on the provided reference field.
     */
    @Override
    public MethodSpec generate(ObjectField target) {
        var recordMethod = mutationConverter.get(target.getMutationType());
        if (recordMethod == null) {
            return MethodSpec.methodBuilder(target.getName()).build();
        }

        var parser = new InputParser(target, processedSchema);
        var spec = getDefaultSpecBuilder(asQueryMethodName(target.getName(), getLocalObject().getName()), TypeName.INT)
                .addParameters(parser.getMethodParameterSpecs(true, false, false));

        var recordInputs = parser.getJOOQRecords();
        if (recordInputs.isEmpty()) {
            return spec.addCode(returnWrap("0")).build();
        }

        var code = CodeBlock.builder();
        String batchInputVariable;
        if (recordInputs.size() == 1) {
            batchInputVariable = inputPrefix(recordInputs.keySet().stream().findFirst().get());
        } else {
            batchInputVariable = VARIABLE_RECORD_LIST;
            code.declareNew(VARIABLE_RECORD_LIST, ARRAY_LIST.className);
            recordInputs.forEach((name, type) -> code.addStatement("$N.$L($N)", VARIABLE_RECORD_LIST, type.isIterableWrapped() ? "addAll" : "add", inputPrefix(name)));
        }

        if (target.hasMutationType() && target.getMutationType() == MutationType.UPSERT) {
            recordInputs.entrySet().stream()
                    .map(it -> findNodeIdInJooqRecordInputTypes(it.getKey(), it.getValue()))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst()
                    .ifPresent(it -> {
                        var recordInputName = inputPrefix(it.getKey());
                        var inputType = it.getValue();
                        var nodeType = processedSchema.getNodeTypeForNodeIdField(inputType);
                        if (nodeType.isPresent()) {
                            String tableName = nodeType.get().getTable().getName();
                            var columnNames = getNodeIdKeyColumnNames(nodeType.get().getKeyColumns(), tableName);
                            if (!columnNames.isEmpty()) {
                                var isIterable = recordInputs.get(it.getKey()).isIterableWrapped();
                                code.addIf(isIterable, "$N.forEach($N -> {\n", recordInputName, VAR_ITERATOR);
                                var variableName = isIterable ? VAR_ITERATOR : recordInputName;
                                columnNames.forEach(columnName -> code.addStatement("$N.changed($N.$N, true)", variableName, tableName, columnName));
                                code.addIf(isIterable, "});\n");
                            }
                        }
                    });
        }

        code.addStatement(
                "return $N.transactionResult($L -> $T.stream($T.using($N).$L($N).execute()).sum())",
                VariableNames.VAR_CONTEXT,
                CONFIG,
                ARRAYS.className,
                DSL.className,
                CONFIG,
                recordMethod,
                batchInputVariable
        );

        return spec
                .addCode(code.build())
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        var localObject = getLocalObject();
        if (localObject.isExplicitlyNotGenerated()) {
            return List.of();
        }
        return localObject
                .getFields()
                .stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(ObjectField::hasMutationType)
                .filter(it -> !processedSchema.isDeleteMutationWithReturning(it))
                .filter(it -> !processedSchema.isInsertMutationWithReturning(it))
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }

    private List<String> getNodeIdKeyColumnNames(List<String> keyColumns, String tableName) {
        if (keyColumns.isEmpty()) {
            return TableReflection.getPrimaryKeyForTableJavaFieldName(tableName)
                    .map(it -> TableReflection.getJavaFieldNamesForKeyInTableJavaFieldName(tableName, it))
                    .orElse(Collections.emptyList());
        } else  {
            return keyColumns;
        }
    }

    private Optional<Map.Entry<String, GenerationField>> findNodeIdInJooqRecordInputTypes(String key, InputField field) {
        return processedSchema.getInputType(field.getTypeName())
                .getFields().stream()
                .filter(processedSchema::isNodeIdField)
                .findFirst()
                .map(it -> Map.entry(key, it));
    }
}
