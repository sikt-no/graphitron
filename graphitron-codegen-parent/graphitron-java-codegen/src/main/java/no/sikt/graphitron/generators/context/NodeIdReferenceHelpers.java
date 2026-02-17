
package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.mappings.ReflectionHelpers;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.ForeignKey;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.getReferenceNodeIdFields;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.referenceNodeIdColumnsBlock;
import static no.sikt.graphitron.mappings.TableReflection.findImplicitKey;
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

        if (!previousTable.equals(targetTable)) {
            return target.getFieldReferences().stream()
                    .findFirst()
                    .map(fRef -> fRef.hasKey() ? fRef.getKey().getName() : findImplicitKey(previousTable, fRef.getTable().getName()).orElse(null))
                    .stream()
                    .findFirst()
                    .or(() -> findImplicitKey(previousTable, targetTable))
                    .flatMap(TableReflection::getForeignKey);
        }
        return Optional.empty();
    }

    public static CodeBlock getSourceFieldsForForeignKey(GenerationField target, ProcessedSchema schema, CodeBlock targetAlias) {
        var key = getForeignKeyForNodeIdReference(target, schema);

        if (key.isPresent()) {
            var nodeType = schema.getNodeTypeForNodeIdFieldOrThrow(target);
            var container = schema.getRecordType(target.getContainerTypeName());
            return referenceNodeIdColumnsBlock(container, nodeType, key.get(), targetAlias);
        }
        return CodeBlock.empty();
    }


    /**
     * @param nodeType
     * @param field
     * @param tableName
     * @return Returns the fields that corresponds to the nodeId key columns. For references it returns the source fields.
     */

    public static List<String> getKeyFieldsForSourceNodeTable(ObjectDefinition nodeType, GenerationField field, String tableName, ProcessedSchema schema) {
        List<String> keyColumnFields;
        if (tableName.equals(nodeType.getTable().getName())) {
            keyColumnFields = schema.getKeyColumnsForNodeType(nodeType).orElseGet(LinkedList::new)
                    .stream()
                    .map(it -> TableReflection.getJavaFieldName(tableName, it)
                            .orElseThrow(() -> new RuntimeException(String.format("Column %s not found in table %s",it, tableName)))
                    ).toList();
        } else {
            var foreignKey = NodeIdReferenceHelpers.getForeignKeyForNodeIdReference(field, schema)
                    .orElseThrow(() -> new RuntimeException("Cannot find foreign key for nodeId field " + field.getName() + " in " + nodeType.getName()));
            keyColumnFields = getReferenceNodeIdFields(tableName, nodeType, foreignKey);
        }
        return keyColumnFields;
    }

}
