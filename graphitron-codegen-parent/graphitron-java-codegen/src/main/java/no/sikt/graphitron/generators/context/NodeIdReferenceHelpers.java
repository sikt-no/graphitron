
package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.helpers.NodeConfiguration;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.Field;
import org.jooq.ForeignKey;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;

import static no.sikt.graphitron.mappings.TableReflection.findImplicitKey;
import static no.sikt.graphitron.mappings.TableReflection.getJavaFieldName;

/**
 * Utility methods for determining whether a {@literal @}nodeId field is a cross-table reference
 * and resolving the foreign key and column mappings needed for the generated code.
 */
public class NodeIdReferenceHelpers {

    /**
     * Determines whether a {@literal @}nodeId field is a reference.
     * Returns {@code true} when the field has explicit {@literal @}reference directives, or when
     * its node type's target table differs from {@code currentTable}.
     *
     * @param field        the {@literal @}nodeId field
     * @param schema       the processed schema.
     * @param currentTable the current table in which the field is currently being resolved, or {@code null} if unknown.
     * @return {@code true} if the field references a node on a different table.
     */
    public static boolean isNodeIdReferenceField(GenerationField field, ProcessedSchema schema, JOOQMapping currentTable) {
        if (!schema.isNodeIdField(field)) {
            return false;
        }
        if (field.hasFieldReferences()) {
            return true;
        }
        return currentTable != null && !schema.getNodeConfigurationForNodeIdFieldOrThrow(field).targetTable().equals(currentTable);
    }

    /**
     * Determines whether a {@literal @}nodeId field is a reference by resolving the current table
     * from the field's container type or its previous table object.
     *
     * @param objectField the {@literal @}nodeId object field
     * @param schema      the processed schema.
     * @return {@code true} if the field references a node on a different table than its previous table.
     * @see #isNodeIdReferenceField(GenerationField, ProcessedSchema, JOOQMapping)
     */
    public static boolean isNodeIdReferenceField(ObjectField objectField, ProcessedSchema schema) {
        var containerType = objectField.getContainerTypeName();
        var currentTable = schema.isObject(containerType) && schema.getObject(containerType).hasTable()
                ? schema.getObject(containerType).getTable()
                : Optional.ofNullable(schema.getPreviousTableObjectForField(objectField))
                .filter(RecordObjectSpecification::hasTable)
                .map(RecordObjectSpecification::getTable)
                .orElse(null);
        return isNodeIdReferenceField(objectField, schema, currentTable);
    }

    /**
     * Returns the Java column names to use for a {@literal @}nodeId field given the current table context.
     * For references, maps the node's key columns through the foreign key to the current table's columns.
     * Returns the node configuration's key columns directly if the field is not a reference.
     *
     * @param field        the field carrying the {@literal @}nodeId directive.
     * @param schema       the processed schema.
     * @param currentTable the current table in which the field is currently being resolved, or {@code null} if unknown.
     * @return ordered list of Java column names for the key fields.
     */
    public static List<String> resolveColumnNamesForNodeIdField(GenerationField field, ProcessedSchema schema, JOOQMapping currentTable) {
        if (isNodeIdReferenceField(field, schema, currentTable)) {
            var foreignKey = findForeignKeyForNodeIdField(field, schema, currentTable)
                    .orElseThrow(() -> new RuntimeException("Cannot find foreign key for nodeId field " + field.formatPath()));
            return mapKeyColumnsThroughForeignKey(currentTable, schema.getNodeConfigurationForNodeIdFieldOrThrow(field), foreignKey);
        }
        return schema.getNodeConfigurationForNodeIdFieldOrThrow(field).keyColumnsJavaNames();
    }

    /**
     * Resolves the foreign key connecting {@code currentTable} to the node type's target table
     * for a {@literal @}nodeId reference field. Returns empty if the field is not a reference.
     *
     * @param field        the {@literal @}nodeId field
     * @param schema       the processed schema.
     * @param currentTable the current table in which the field is currently being resolved, or {@code null} if unknown.
     * @return the foreign key, or empty if the field is not a reference.
     */
    public static Optional<ForeignKey<?,?>> findForeignKeyForNodeIdField(GenerationField field, ProcessedSchema schema, JOOQMapping currentTable) {
        if (!isNodeIdReferenceField(field, schema, currentTable)) {
            return Optional.empty();
        }
        return resolveForeignKey(field, currentTable, schema.getNodeConfigurationForNodeIdFieldOrThrow(field).targetTable());
    }

    private static Optional<ForeignKey<?,?>> resolveForeignKey(GenerationField field, JOOQMapping previousTable, JOOQMapping targetTable) {
        return field.getFieldReferences().stream()
                .findFirst()
                .map(fRef -> fRef.hasKey()
                        ? Optional.of(fRef.getKey().getName())
                        : findImplicitKey(previousTable.getName(), fRef.getTable().getName()))
                .orElseGet(() -> findImplicitKey(previousTable.getName(), targetTable.getName()))
                .flatMap(TableReflection::getForeignKey);
    }

    private static List<String> mapKeyColumnsThroughForeignKey(JOOQMapping currentTable, NodeConfiguration targetNodeConfiguration, ForeignKey<?,?> fk) {
        var sourceColumns = fk.getFields();
        var targetColumns = fk.getInverseKey().getFields();

        var mapping = new TreeMap<String, Field<?>>(String.CASE_INSENSITIVE_ORDER);
        IntStream.range(0, sourceColumns.size())
                .forEach(i -> mapping.put(targetColumns.get(i).getName(), sourceColumns.get(i)));

        return targetNodeConfiguration.keyColumnsJavaNames().stream()
                .map(it -> Optional.ofNullable(mapping.get(it))
                        .orElseThrow(() -> new IllegalArgumentException("Node ID field " + it + " is not found in foreign key " + fk.getName() + "'s fields.")))
                .map(it -> getJavaFieldName(currentTable.getName(), it.getName()).orElseThrow())
                .toList();
    }
}