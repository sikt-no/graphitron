package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.ForeignKey;
import org.jooq.Key;
import org.jooq.Typed;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.wrapRow;
import static no.sikt.graphitron.mappings.TableReflection.*;

public class ResolverKeyHelpers {

    /**
     * Get map of field names to keys used in the first step of the reference in resolver fields.
     * The key could either be a foreign key or the primary key of the current table.
     *
     * @param fields The fields to find keys for
     * @param schema The processed schema
     * @return The map of field names and keys
     */
    public static LinkedHashMap<String, Key<?>> getKeyMapForResolverFields(List<? extends GenerationField> fields, ProcessedSchema schema) {
        return fields.stream()
                .filter(GenerationTarget::isGeneratedWithResolver)
                .collect(Collectors.toMap(GenerationField::getName, it ->
                        findKeyForResolverField(it, schema), (a, b) -> b, LinkedHashMap::new));
    }

    /**
     * Get the set of keys used in the resolver fields given a list of fields.
     * The key could either be a foreign key or the primary key of the current table.
     *
     * @param fields The fields to find keys for
     * @param schema The processed schema
     * @return The map of field names and keys
     */
    public static LinkedHashSet<Key<?>> getKeySetForResolverFields(List<? extends GenerationField> fields, ProcessedSchema schema) {
        return new LinkedHashSet<>(getKeyMapForResolverFields(fields, schema).values());
    }

    /**
     * Finds the key used in the first step when resolving a resolver field
     *
     * @param field           The resolver field
     * @param processedSchema The processed schema
     * @return The key used in the first step when resolving a resolver field
     */
    public static Key<?> findKeyForResolverField(GenerationField field, ProcessedSchema processedSchema) {
        var container = processedSchema.getRecordType(field.getContainerTypeName());

        var containerTypeTable = container.hasTable() ?
                container.getTable()
                : processedSchema.getPreviousTableObjectForObject(container).getTable();

        var tableFromFieldType = processedSchema.isRecordType(field) ? processedSchema.getRecordType(field).getTable() : null;

        ForeignKey<?, ?> foreignKey;
        var primaryKeyOptional = getPrimaryKeyForTable(containerTypeTable.getName());

        if (processedSchema.isScalar(field.getTypeName()) && !field.hasFieldReferences()) {
            throw new RuntimeException("Cannot resolve reference for scalar field '" + field.getName() + "' in type '" + field.getContainerTypeName() + "'.");
        } else if (field.hasFieldReferences()) {
            var firstRef = field.getFieldReferences().stream().findFirst().get();
            String keyName;
            Optional<String> implicitKey = firstRef.hasTable() ? findImplicitKey(containerTypeTable.getName(), firstRef.getTable().getName()) : Optional.empty();

            if (firstRef.hasKey()) {
                keyName = firstRef.getKey().getName();
            } else if (firstRef.hasTableCondition() && implicitKey.isEmpty()) {
                    return primaryKeyOptional
                            .orElseThrow(() -> new IllegalArgumentException(
                                    String.format("Code generation failed for %s.%s as the table %s must have a primary key in order to reference another table without a foreign key.",
                                            field.getContainerTypeName(), field.getName(), containerTypeTable.getName())));
            } else {
                keyName = implicitKey.stream().findFirst()
                        .orElseThrow(() -> new RuntimeException("Cannot find implicit key for field '" + field.getName() + "' in type '" + field.getContainerTypeName() + "'."));
            }

            foreignKey = getForeignKey(keyName)
                    .orElseThrow(() -> new RuntimeException("Cannot find key with name " + firstRef.getKey().getName()));
        } else {
            foreignKey = getForeignKey(findImplicitKey(containerTypeTable.getName(), (tableFromFieldType != null ? tableFromFieldType : containerTypeTable).getName())
                    .orElseThrow(() -> new RuntimeException("Cannot find implicit key for field '" + field.getName() + "' in type '" + field.getContainerTypeName() + "'.")))
                    .orElseThrow();
        }

        if (!foreignKey.getTable().getName().equalsIgnoreCase(containerTypeTable.getName())) { // Reverse reference
            return primaryKeyOptional
                    .orElseThrow(() -> new IllegalArgumentException(
                            String.format("Code generation failed for %s.%s as the table %s must have a primary key in order to reference another table in a listed field.",
                                    field.getContainerTypeName(), field.getName(), containerTypeTable.getName())));
        }
        return foreignKey;
    }

    /**
     * Returns the select code for the columns of a key.
     *
     * @param key               The key
     * @param aliasVariableName The variable name for the table alias
     * @return Select code for the columns in the key
     */
    public static CodeBlock getSelectKeyColumnRow(Key<?> key, String tableName, String aliasVariableName) {
        return wrapRow(
                getJavaFieldNamesForKey(tableName, key)
                        .stream()
                        .map(it -> CodeBlock.of("$N.$L", aliasVariableName, it))
                        .collect(CodeBlock.joining(", "))
        );
    }

    /**
     * Get the TypeName for the key variable in the DTO
     *
     * @param key The key
     * @return TypeName of the key variable
     */
    public static TypeName getKeyTypeName(Key<?> key) {
        var keyFields = key.getFields();

        if (keyFields.size() > 22) {
            throw new RuntimeException(String.format("Key '%s' has more than 22 fields, which is not supported.", key.getName()));
        }
        return ParameterizedTypeName.get(
                ClassName.get("org.jooq", String.format("Record%d", keyFields.size())),
                keyFields.stream().map(Typed::getType).map(ClassName::get).toArray(ClassName[]::new)
        );
    }
}
