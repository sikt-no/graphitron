
package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.ForeignKey;

import java.util.Optional;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.referenceNodeIdColumnsBlock;
import static no.sikt.graphitron.mappings.TableReflection.findImplicitKeyGivenTableJavaFieldNames;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_QUERY;

public class NodeIdReferenceHelpers {

    public static Optional<ForeignKey<?,?>> getForeignKeyForNodeIdReference(GenerationField target, ProcessedSchema schema) {
        if (!schema.isNodeIdField(target) || !schema.hasJOOQRecord(target.getContainerTypeName()) || target.getContainerTypeName().equals(SCHEMA_QUERY.getName())) {
            return Optional.empty();
        }
        var targetTable = schema.getNodeTypeForNodeIdFieldOrThrow(target).getTable().getName();
        var previousTable = schema.getRecordType(target.getContainerTypeName()).getTable().getName();
        if (!previousTable.equals(targetTable)) {
            return target.getFieldReferences().stream()
                    .findFirst()
                    .map(fRef -> fRef.hasKey() ? fRef.getKey().getName() : findImplicitKeyGivenTableJavaFieldNames(previousTable, fRef.getTable().getName()).orElse(null))
                    .stream()
                    .findFirst()
                    .or(() -> findImplicitKeyGivenTableJavaFieldNames(previousTable, targetTable))
                    .flatMap(TableReflection::getFkByFkJavaFieldName);
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
}
