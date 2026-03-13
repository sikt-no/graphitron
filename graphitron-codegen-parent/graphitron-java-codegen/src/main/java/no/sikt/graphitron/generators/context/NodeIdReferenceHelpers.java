
package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.definitions.helpers.NodeConfiguration;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.mappings.ReflectionHelpers;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.Field;
import org.jooq.ForeignKey;

import java.util.LinkedList;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.mappings.TableReflection.findImplicitKey;
import static no.sikt.graphitron.mappings.TableReflection.getJavaFieldName;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_QUERY;

public class NodeIdReferenceHelpers {

    public static Optional<ForeignKey<?,?>> getForeignKeyForNodeIdReference(GenerationField target, ProcessedSchema schema) {
        if (!schema.isNodeIdField(target) || !schema.hasRecord(target.getContainerTypeName()) || target.getContainerTypeName().equals(SCHEMA_QUERY.getName())) {
            return Optional.empty();
        }
        var targetTable = schema.getNodeTypeForNodeIdFieldOrThrow(target).getTable().getName();

        String previousTable;
        if (schema.hasJOOQRecord(target.getContainerTypeName())) {
            previousTable = schema.getRecordType(target.getContainerTypeName()).getTable().getName();
        } else {
            var optionalPreviousTable = ReflectionHelpers.getJooqRecordClassForNodeIdInputField(target, schema);
            if (optionalPreviousTable.isPresent()) {
                previousTable = TableReflection.getTableJavaFieldNameForRecordClass(optionalPreviousTable.get()).orElseThrow();
            } else {
                return Optional.empty();
            }
        }

        if (previousTable.equals(targetTable) && target.getFieldReferences().isEmpty()) {
            return Optional.empty();
        }
        return target.getFieldReferences().stream()
                .findFirst()
                .map(fRef -> fRef.hasKey() ? fRef.getKey().getName() : findImplicitKey(previousTable, fRef.getTable().getName()).orElse(null))
                .stream()
                .findFirst()
                .or(() -> findImplicitKey(previousTable, targetTable))
                .flatMap(TableReflection::getForeignKey);
    }

    public static CodeBlock maplksjdasdjaoisd(GenerationField target, ProcessedSchema schema, CodeBlock targetAlias) {
        var key = getForeignKeyForNodeIdReference(target, schema);

        if (key.isPresent()) {
            var nodeType = schema.getNodeConfigurationForNodeIdFieldOrThrow(target);
            var container = schema.getRecordType(target.getContainerTypeName());
            var mappedFields = getNodeIdReferenceFields(container.getTable().getName(), nodeType, key.get());
            return commaSeparatedTableFieldsBlock(targetAlias, mappedFields);
        }
        return CodeBlock.empty();
    }


    /**
     * @param nodeType      The nodeId's type
     * @param field         The field with the nodeId directive
     * @param currentTable     The target jOOQ table name.
     * @return Returns the fields that corresponds to the nodeId key columns. For references, it returns the source fields.
     */

    public static LinkedList<String> getKeyFieldsForSourceNodeTable(ObjectDefinition nodeType, GenerationField field, String currentTable, ProcessedSchema schema) {
        if (currentTable.equals(nodeType.getTable().getName()) && field.getFieldReferences().isEmpty()) {
            return schema.getNodeConfigurationForTypeOrThrow(nodeType).keyColumnsJavaNames()
                    .stream()
                    .map(it -> TableReflection.getJavaFieldName(currentTable, it)
                            .orElseThrow(() -> new RuntimeException(String.format("Column %s not found in table %s", it, currentTable)))
                    ).collect(Collectors.toCollection(LinkedList<String>::new));
        }
        var foreignKey = getForeignKeyForNodeIdReference(field, schema)
                .orElseThrow(() -> new RuntimeException("Cannot find foreign key for nodeId field " + field.getName() + " in " + nodeType.getName()));
        return getNodeIdReferenceFields(currentTable, schema.getNodeConfigurationForTypeOrThrow(nodeType), foreignKey);
    }

    public static LinkedList<String> getNodeIdReferenceFields(String currentTable, NodeConfiguration targetNodeConfiguration, ForeignKey<?,?> fk) {
        var sourceColumns = fk.getFields();
        var targetColumns = fk.getInverseKey().getFields();

        var mapping = new TreeMap<String, Field<?>>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < sourceColumns.size(); i++) {
            mapping.put(targetColumns.get(i).getName(), sourceColumns.get(i));
        }

        return targetNodeConfiguration.keyColumnsJavaNames().stream()
                .map(it -> Optional.ofNullable(mapping.get(it))
                        .orElseThrow(() -> new IllegalArgumentException("Node ID field " + it + " is not found in foreign key " + fk.getName() + "'s fields.")))
                .map(it -> getJavaFieldName(currentTable, it.getName()).orElseThrow())
                .collect(Collectors.toCollection(LinkedList::new));
    }

}
