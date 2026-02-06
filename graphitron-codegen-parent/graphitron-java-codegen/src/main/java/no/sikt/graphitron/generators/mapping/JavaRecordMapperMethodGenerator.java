package no.sikt.graphitron.generators.mapping;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.AbstractMapperMethodGenerator;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.toCamelCase;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.METHOD_SET_RECORD_REFERENCE_ID;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_ARGS;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_STRATEGY;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_PATH_HERE;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.selectionSetLookup;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.outputPrefix;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.namedIteratorPrefix;

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
            fieldCode.add(generateNodeIdGroupCode(group, context));
            fieldCode.add("\n");
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
            if (processedSchema.isNodeIdFieldProducingJooqRecord(field)) {
                String targetName = field.getJavaRecordMethodMapping(true).getName();
                var jooqRecordClass = processedSchema.getJooqRecordClassForNodeIdField(field);

                groups.computeIfAbsent(targetName, k -> new NodeIdFieldGroup(k, jooqRecordClass))
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
            .collect(Collectors.toList());
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
        var hasValueVarName = targetVarName + "_hasValue";
        var jooqRecordClass = group.getJooqRecordClass();

        // Detect overlapping columns that need runtime validation
        var overlappingColumns = detectOverlappingColumns(group);

        // Declare the jOOQ record variable and hasValue flag
        code.addStatement("$T $N = new $T()", jooqRecordClass, targetVarName, jooqRecordClass);
        code.addStatement("boolean $N = false", hasValueVarName);
        code.add("\n");

        // Generate code for each @nodeId field in the group
        for (var field : group.getFields()) {
            code.add(generateSingleNodeIdFieldCode(field, targetVarName, hasValueVarName, context, overlappingColumns.keySet()));
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
     * @return CodeBlock containing the generated code
     */
    private CodeBlock generateSingleNodeIdFieldCode(
            GenerationField field,
            String targetVarName,
            String hasValueVarName,
            MapperContext context,
            Set<String> overlappingColumns) {

        var code = CodeBlock.builder();
        var nodeType = processedSchema.getNodeTypeForNodeIdFieldOrThrow(field);
        var jooqRecordClass = processedSchema.getJooqRecordClassForNodeIdField(field);
        var columnsBlock = generateNodeIdColumnsBlock(jooqRecordClass, nodeType);

        // Get the node ID value from input
        var sourceName = context.getSourceName();
        var inputVar = namedIteratorPrefix(sourceName);
        var getterMapping = new MethodMapping(field.getName());

        code.beginControlFlow("if ($N.contains($N + $S))", VAR_ARGS, VAR_PATH_HERE, field.getName());
        code.addStatement("var nodeIdValue = $N.$L()", inputVar, getterMapping.asGet());

        code.beginControlFlow("if (nodeIdValue != null)");

        // Generate overlap validation if this field writes to any overlapping columns
        if (!overlappingColumns.isEmpty()) {
            var fieldKeyColumns = getKeyColumnsForNodeType(nodeType);
            var hasOverlap = fieldKeyColumns.stream().anyMatch(overlappingColumns::contains);
            if (hasOverlap) {
                code.add(generateOverlapValidationCode(field, targetVarName, nodeType, overlappingColumns, jooqRecordClass));
            }
        }

        code.addStatement("$N = true", hasValueVarName);
        code.addStatement("$N.$L($N, nodeIdValue, $S, $L)",
            VAR_NODE_STRATEGY,
            METHOD_SET_RECORD_REFERENCE_ID,
            targetVarName,
            nodeType.getTypeId(),
            columnsBlock
        );

        code.endControlFlow(); // if nodeIdValue != null
        code.endControlFlow(); // if args.contains
        code.add("\n");

        return code.build();
    }

    /**
     * Generates jOOQ column references for a @nodeId field targeting a jOOQ record.
     * The columns are from the node type's key columns, referenced on the target table.
     * @param jooqRecordClass The target jOOQ record class
     * @param nodeType The node type definition
     * @return CodeBlock with comma-separated column references like "Table.TABLE.COLUMN1, Table.TABLE.COLUMN2"
     */
    private CodeBlock generateNodeIdColumnsBlock(Class<?> jooqRecordClass, ObjectDefinition nodeType) {
        var tableName = TableReflection.getTableNameForRecordClass(jooqRecordClass)
            .orElseThrow(() -> new RuntimeException("Cannot find table for jOOQ record class: " + jooqRecordClass.getName()));
        var tableClass = TableReflection.getTableByJavaFieldName(tableName)
            .orElseThrow(() -> new RuntimeException("Unknown table " + tableName))
            .getClass();

        // Get the key columns for this node type
        List<String> keyColumns = getKeyColumnsForNodeType(nodeType);

        // Generate column references on the target table
        List<CodeBlock> columnBlocks = new ArrayList<>();
        for (String columnName : keyColumns) {
            var javaFieldName = TableReflection.getJavaFieldName(tableName, columnName)
                .orElseThrow(() -> new RuntimeException("Column " + columnName + " not found in table " + tableName));
            columnBlocks.add(CodeBlock.of("$T.$N.$N", tableClass, tableName, javaFieldName));
        }
        return columnBlocks.stream().collect(CodeBlock.joining(", "));
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
                .collect(Collectors.toList());
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
            var keyColumns = getKeyColumnsForNodeType(nodeType);

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
            Class<?> jooqRecordClass) {

        var code = CodeBlock.builder();
        var keyColumns = getKeyColumnsForNodeType(nodeType);

        // Get table info for generating column references
        var tableName = TableReflection.getTableNameForRecordClass(jooqRecordClass)
            .orElseThrow(() -> new RuntimeException("Cannot find table for jOOQ record class: " + jooqRecordClass.getName()));
        var tableClass = TableReflection.getTableByJavaFieldName(tableName)
            .orElseThrow(() -> new RuntimeException("Unknown table " + tableName))
            .getClass();

        // For each key column that overlaps with other fields
        for (int i = 0; i < keyColumns.size(); i++) {
            var columnName = keyColumns.get(i);
            if (!overlappingColumns.contains(columnName)) {
                continue;
            }

            var javaFieldName = TableReflection.getJavaFieldName(tableName, columnName)
                .orElseThrow(() -> new RuntimeException("Column " + columnName + " not found in table " + tableName));
            var getterName = "get" + toCamelCase(columnName).substring(0, 1).toUpperCase()
                + toCamelCase(columnName).substring(1);

            // Check if this column already has a value
            code.addStatement("var existing_$L = $N.$L()", toCamelCase(columnName), targetVarName, getterName);
            code.beginControlFlow("if (existing_$L != null)", toCamelCase(columnName));

            // Unpack the new value to compare
            code.addStatement("var newValues = $N.unpackIdValues($S, nodeIdValue, $T.$N.$N)",
                VAR_NODE_STRATEGY,
                nodeType.getTypeId(),
                tableClass,
                tableName,
                javaFieldName
            );

            // Compare and throw if different
            code.beginControlFlow("if (!existing_$L.toString().equals(newValues[$L]))",
                toCamelCase(columnName), i);
            code.addStatement("throw new $T($S + existing_$L + $S + newValues[$L])",
                IllegalArgumentException.class,
                "Conflicting values for column " + columnName + ": existing=",
                toCamelCase(columnName),
                ", new=",
                i
            );
            code.endControlFlow();
            code.endControlFlow();
        }

        return code.build();
    }
}
