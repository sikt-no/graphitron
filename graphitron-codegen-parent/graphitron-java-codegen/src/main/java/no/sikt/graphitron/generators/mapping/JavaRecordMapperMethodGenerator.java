package no.sikt.graphitron.generators.mapping;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.AbstractMapperMethodGenerator;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.generators.context.NodeIdReferenceHelpers;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.LIST;
import static no.sikt.graphitron.mappings.JavaPoetClassName.MAPPER_HELPER;

public class JavaRecordMapperMethodGenerator extends AbstractMapperMethodGenerator {
    public JavaRecordMapperMethodGenerator(GenerationField localField, ProcessedSchema processedSchema, boolean toRecord) {
        super(localField, processedSchema, toRecord);
    }

    @Override
    public MethodSpec generate(GenerationField target) {
        return getMapperSpecBuilder(target).build();
    }

    /**
     * @return Code for setting the record data from input types.
     */
    @NotNull
    @Override
    protected CodeBlock iterateRecords(MapperContext context) {
        if (context.isIterable() && !context.hasRecordReference()) {
            return CodeBlock.empty(); // Can not allow this, because input type may contain multiple fields. These can not be mapped to a single field in any reasonable way.
        }

        var fieldCode = CodeBlock.builder();
        var allFields = context
                .getTargetType()
                .getFields()
                .stream()
                .filter(it -> !(it.isExplicitlyNotGenerated() || (it.isResolver() && toRecord)))
                .toList();

        // Group @nodeId fields targeting jOOQ records
        var nodeIdGroups = groupNodeIdFields(allFields);
        var regularFields = getNonGroupedFields(allFields, nodeIdGroups);

        // Generate code for @nodeId groups first
        for (var group : nodeIdGroups.values()) {
            fieldCode
                    .add(generateNodeIdGroupCode(group, context))
                    .add("\n");
        }

        // Process regular fields as before
        for (var innerField : regularFields) {
            var innerContext = context.iterateContext(innerField);
            if (innerContext.targetCanNotBeMapped()) {
                continue;
            }

            var varName = inputPrefix(innerContext.getHelperVariableName());
            var innerCode = CodeBlock.builder();
            if (!innerContext.getTarget().isResolver()) {
                if (!innerContext.targetIsType()) {
                    innerCode.add(innerContext.getFieldSetMappingBlock());
                } else if (innerContext.hasRecordReference()) {
                    innerCode.add(innerContext.getRecordSetMappingBlock());
                } else {
                    innerCode.add(iterateRecords(innerContext));
                }
            } else {
                innerCode.add(innerContext.getResolverKeySetMappingBlock(varName));
            }

            if (!innerCode.isEmpty()) {
                var notAlreadyDefined = innerContext.variableNotAlreadyDeclared();
                var shouldDeclareVariable = notAlreadyDefined || innerContext.getTarget().isResolver();
                var nullBlock = CodeBlock.ofIf(shouldDeclareVariable, "$N != null && ", varName);
                fieldCode
                        .declareIf(shouldDeclareVariable, varName, innerContext.getSourceGetCallBlock())
                        .beginControlFlow("if ($L$L)", nullBlock, selectionSetLookup(innerContext.getPath(), false, toRecord))
                        .add(innerCode.build())
                        .endControlFlow()
                        .add("\n");
            }
        }

        return context.wrapFields(fieldCode.build());
    }

    @Override
    public boolean mapsJavaRecord() {
        return true;
    }

    /**
     * Groups @nodeId fields that target the same jOOQ record field.
     * Non-@nodeId fields and @nodeId fields without jOOQ record targets are not grouped.
     */
    private Map<String, NodeIdFieldGroup> groupNodeIdFields(List<? extends GenerationField> fields) {
        Map<String, NodeIdFieldGroup> groups = new LinkedHashMap<>();

        for (var field : fields) {
            var jooqRecordClass = processedSchema.getJooqRecordClassForNodeIdField(field);
            if (jooqRecordClass.isPresent()) {
                String targetName = field.getJavaRecordMethodMapping(true).getName();
                groups.computeIfAbsent(targetName, k -> new NodeIdFieldGroup(k, jooqRecordClass.get()))
                        .addField(field);
            }
        }

        return groups;
    }

    /**
     * Returns fields that are NOT part of any @nodeId group (processed normally).
     */
    private List<? extends GenerationField> getNonGroupedFields(
            List<? extends GenerationField> allFields,
            Map<String, NodeIdFieldGroup> groups) {
        Set<GenerationField> groupedFields = groups.values().stream()
            .flatMap(g -> g.getFields().stream())
            .collect(Collectors.toSet());

        return allFields.stream()
            .filter(f -> !groupedFields.contains(f))
            .toList();
    }

    /**
     * Generates code to create and populate a jOOQ record from @nodeId fields.
     * @param group The group of @nodeId fields targeting the same jOOQ record
     * @param context The current mapper context
     * @return CodeBlock containing the generated code
     */
    private CodeBlock generateNodeIdGroupCode(NodeIdFieldGroup group, MapperContext context) {
        var code = CodeBlock.builder();
        var targetVarName = inputPrefix(group.getTargetFieldName());
        var hasValueVarName = targetVarName + "HasValue";
        var jooqRecordClass = group.getJooqRecordClass();

        // Detect overlapping columns that need runtime validation
        var overlappingColumns = detectOverlappingColumns(group);

        // Declare the jOOQ record variable and hasValue flag
        code
            .addStatement("$T $N = new $T()", jooqRecordClass, targetVarName, jooqRecordClass)
            .addStatement("boolean $N = false", hasValueVarName);

        // Generate code for each @nodeId field in the group
        for (var field : group.getFields()) {
            code.add(generateSingleNodeIdFieldCode(field, targetVarName, hasValueVarName, context, overlappingColumns.keySet(), jooqRecordClass));
        }

        // Set the final value on the output record
        var outputVar = outputPrefix(context.getTargetName());
        var setterMapping = new MethodMapping(group.getTargetFieldName());
        code.addStatement("$N.$L($N ? $N : null)", outputVar, setterMapping.asSet(), hasValueVarName, targetVarName);

        return code.build();
    }

    /**
     * Generates code for a single @nodeId field within a group.
     * @param field The @nodeId field
     * @param targetVarName The variable name of the target jOOQ record
     * @param hasValueVarName The variable name of the hasValue flag
     * @param context The current mapper context
     * @param overlappingColumns Set of column names that are written by multiple fields in the group
     * @param jooqRecordClass The target jOOQ record class (from the group)
     * @return CodeBlock containing the generated code
     */
    private CodeBlock generateSingleNodeIdFieldCode(
            GenerationField field,
            String targetVarName,
            String hasValueVarName,
            MapperContext context,
            Set<String> overlappingColumns,
            Class<?> jooqRecordClass) {

        var nodeType = processedSchema.getNodeTypeForNodeIdFieldOrThrow(field);
        var columnsBlock = generateNodeIdColumnsBlock(jooqRecordClass, nodeType, field);

        // Get the node ID value from input
        var sourceName = context.getSourceName();
        var inputVar = namedIteratorPrefix(sourceName);
        var getterMapping = new MethodMapping(field.getName());

        var code = CodeBlock.builder()
                .beginControlFlow("if ($N.contains($N + $S))", VAR_ARGS, VAR_PATH_HERE, field.getName())
                .declare(VAR_NODE_ID_VALUE, asMethodCall(inputVar, getterMapping.asGet()))
                .beginControlFlow("if ($N != null)", VAR_NODE_ID_VALUE);

        // Generate overlap validation if this field writes to any overlapping columns
        if (!overlappingColumns.isEmpty()) {
            var fieldKeyColumns = getKeyFieldsForSourceNodeTable(nodeType, field, getTableName(jooqRecordClass));
            var hasOverlap = fieldKeyColumns.stream().anyMatch(overlappingColumns::contains);
            code.addIf(hasOverlap, () -> generateOverlapValidationCode(field, targetVarName, nodeType, overlappingColumns, jooqRecordClass, VAR_NODE_ID_VALUE));
        }

        return code
                .addStatement("$N = true", hasValueVarName)
                .addStatement(
                        "$N.$L($N, $N, $S, $L)",
                        VAR_NODE_STRATEGY,
                        METHOD_SET_RECORD_REFERENCE_ID,
                        targetVarName,
                        VAR_NODE_ID_VALUE,
                        nodeType.getTypeId(),
                        columnsBlock)
                .endControlFlow()
                .endControlFlow()
                .add("\n")
                .build();
    }

    /**
     * Generates jOOQ column references for a @nodeId field targeting a jOOQ record.
     * The columns are from the node type's key columns, referenced on the target table.
     *
     * @param jooqRecordClass The target jOOQ record class
     * @param nodeType        The node type definition
     * @param field           The nodeId field
     * @return CodeBlock with comma-separated column references like "Table.TABLE.COLUMN1, Table.TABLE.COLUMN2"
     */
    private CodeBlock generateNodeIdColumnsBlock(Class<?> jooqRecordClass, ObjectDefinition nodeType, GenerationField field) {
        var tableName = getTableName(jooqRecordClass);
        var tableClass = TableReflection.getTableByJavaFieldName(tableName)
            .orElseThrow(() -> new RuntimeException("Unknown table " + tableName))
            .getClass();

        var keyColumnFields = getKeyFieldsForSourceNodeTable(nodeType, field, tableName);
        List<CodeBlock> columnBlocks = new ArrayList<>();
        for (var keyColumnField : keyColumnFields) {
            columnBlocks.add(CodeBlock.of("$T.$N.$N", tableClass, tableName, keyColumnField));
        }

        return CodeBlock.join(columnBlocks, ", ");
    }

    /**
     * @param nodeType
     * @param field
     * @param tableName
     * @return Returns the fields that corresponds to the nodeId key columns. For references it returns the source fields.
     */

    private List<String> getKeyFieldsForSourceNodeTable(ObjectDefinition nodeType, GenerationField field, String tableName) {
        List<String> keyColumnFields;
        if (tableName.equals(nodeType.getTable().getName())) {
            keyColumnFields = getKeyColumnsForNodeType(nodeType)
                    .stream()
                    .map(it -> TableReflection.getJavaFieldName(tableName, it)
                            .orElseThrow(() -> new RuntimeException(String.format("Column %s not found in table %s",it, tableName)))
                    ).toList();
        } else {
            var foreignKey = NodeIdReferenceHelpers.getForeignKeyForNodeIdReference(field, processedSchema)
                    .orElseThrow(() -> new RuntimeException("Cannot find foreign key for nodeId field " + field.getName() + " in " + nodeType.getName()));
            keyColumnFields = getReferenceNodeIdFields(tableName, nodeType, foreignKey);
        }
        return keyColumnFields;
    }

    /**
     * Gets the key columns for a node type (either custom or from primary key).
     * @param nodeType The node type definition
     * @return List of column names that make up the node ID
     */
    private List<String> getKeyColumnsForNodeType(ObjectDefinition nodeType) {
        if (nodeType.hasCustomKeyColumns()) {
            return new ArrayList<>(nodeType.getKeyColumns());
        } else {
            var nodeTableName = nodeType.getTable().getName();
            var pk = TableReflection.getPrimaryKeyForTable(nodeTableName)
                .orElseThrow(() -> new RuntimeException("Cannot find primary key for table " + nodeTableName));
            return pk.getFields().stream()
                .map(org.jooq.Field::getName)
                .toList();
        }
    }

    /**
     * Detects columns that are written by multiple @nodeId fields in a group.
     * Returns a map of column name -> list of fields that write to it.
     * Only returns columns with multiple writers (actual overlaps).
     * @param group The group of @nodeId fields
     * @return Map of overlapping column names to the fields that write to them
     */
    private Map<String, List<GenerationField>> detectOverlappingColumns(NodeIdFieldGroup group) {
        Map<String, List<GenerationField>> columnToFields = new LinkedHashMap<>();

        for (var field : group.getFields()) {
            var nodeType = processedSchema.getNodeTypeForNodeIdFieldOrThrow(field);
            var tableName = getTableName(group.getJooqRecordClass());

            var keyColumns = getKeyFieldsForSourceNodeTable(nodeType, field, tableName);
            for (var columnName : keyColumns) {
                columnToFields.computeIfAbsent(columnName, k -> new ArrayList<>())
                             .add(field);
            }
        }

        // Return only columns with multiple writers
        return columnToFields.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Generates code to validate that overlapping columns have consistent values.
     * This is called before setting a value when a column might have already been set
     * by a previous @nodeId field in the same group.
     * @param field The current @nodeId field
     * @param targetVarName The variable name of the target jOOQ record
     * @param nodeType The node type definition for this field
     * @param overlappingColumns Set of column names that overlap with other fields
     * @param jooqRecordClass The target jOOQ record class
     * @return CodeBlock containing the validation code
     */
    private CodeBlock generateOverlapValidationCode(
            GenerationField field,
            String targetVarName,
            ObjectDefinition nodeType,
            Set<String> overlappingColumns,
            Class<?> jooqRecordClass,
            String nodeIdValue) {

        var tableName =  getTableName(jooqRecordClass);
        var keyColumns = getKeyFieldsForSourceNodeTable(nodeType, field, tableName);
        var tableClass = TableReflection.getTableByJavaFieldName(tableName)
                .orElseThrow(() -> new RuntimeException("Unknown table " + tableName))
                .getClass();

        var code = CodeBlock.builder();


        // For each key column that overlaps with other fields
        for (int i = 0; i < keyColumns.size(); i++) {
            var columnName = keyColumns.get(i);
            if (!overlappingColumns.contains(columnName)) {
                continue;
            }

            var javaFieldName = TableReflection.getJavaFieldName(tableName, columnName)
                .orElseThrow(() -> new RuntimeException("Column " + columnName + " not found in table " + tableName));

            var nameMapping = new MethodMapping(javaFieldName);

            code.addStatement("$T.$L($N, $L, $N, $S, $L, $S, $L)",
                    MAPPER_HELPER.className,
                    "validateOverlappingNodeIdColumns",
                    VAR_NODE_STRATEGY,
                    nodeIdValue,
                    targetVarName,
                    nodeType.getTypeId(),
                    CodeBlock.of("$T.of($T.$N.$N)", LIST.className, tableClass, tableName, javaFieldName),
                    columnName,
                    CodeBlock.of("($1L) -> $1N.$2L()", VAR_ITERATOR, nameMapping.asCamelGet())
            );
        }

        return code.build();
    }

    private static @NonNull String getTableName(Class<?> jooqRecordClass) {
        return TableReflection.getTableJavaFieldNameForRecordClass(jooqRecordClass)
                .orElseThrow(() -> new RuntimeException("Cannot find table for jOOQ record class: " + jooqRecordClass.getName()));
    }
}
