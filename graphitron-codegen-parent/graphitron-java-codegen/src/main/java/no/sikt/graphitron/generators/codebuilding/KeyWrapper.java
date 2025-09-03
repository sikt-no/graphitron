package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.Key;
import org.jooq.Typed;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphitron.mappings.JavaPoetClassName.LIST;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphql.directives.GenerationDirective.SPLIT_QUERY;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public record KeyWrapper(Key<?> key) {
    public String getDTOVariableName() {
        return uncapitalize(key.getName());
    }

    public String getDTOGetterName() {
        return "get" + capitalize(key.getName());
    }

    /**
     * Get the Row TypeName for the key variable
     *
     * @return Row TypeName of the key variable
     */
    public TypeName getRowTypeName(boolean asList) {
        return getTypeName(false, asList);
    }

    public TypeName getRowTypeName() {
        return getRowTypeName(false);
    }

    /**
     * Get the Record TypeName for the key variable
     *
     * @return Record TypeName of the key variable
     */
    public TypeName getRecordTypeName() {
        return getTypeName(true,false);
    }

    private TypeName getTypeName(boolean asRecordType, boolean asList) {
        var keyFields = key.getFields();

        if (keyFields.size() > 22) {
            throw new RuntimeException(String.format("Key '%s' has more than 22 fields, which is not supported.", key.getName()));
        }
        var parameterized = ParameterizedTypeName.get(
                ClassName.get("org.jooq", String.format("%s%d", asRecordType ? "Record" : "Row", keyFields.size())),
                keyFields.stream().map(Typed::getType).map(ClassName::get).toArray(ClassName[]::new));

        return asList ? ParameterizedTypeName.get(LIST.className, parameterized) : parameterized;
    }

    /**
     * Get map of field names to keys used in the first step of the reference in resolver fields.
     * The key could either be a foreign key or the primary key of the current table.
     *
     * @param fields The fields to find keys for
     * @param schema The processed schema
     * @return The map of field names and keys
     */
    public static LinkedHashMap<String, KeyWrapper> getKeyMapForResolverFields(List<? extends GenerationField> fields, ProcessedSchema schema) {
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
    public static LinkedHashSet<KeyWrapper> getKeySetForResolverFields(List<? extends GenerationField> fields, ProcessedSchema schema) {
        return new LinkedHashSet<>(getKeyMapForResolverFields(fields, schema).values());
    }

    /**
     * Finds the key used in the first step when resolving a resolver field
     *
     * @param field           The resolver field
     * @param processedSchema The processed schema
     * @return Wrapper for the key used in the first step when resolving a resolver field
     */
    public static KeyWrapper findKeyForResolverField(GenerationField field, ProcessedSchema processedSchema) {
        return new KeyWrapper(findKeyForField(field, processedSchema));
    }

    private static Key<?> findKeyForField(GenerationField field, ProcessedSchema processedSchema) {
        if (!field.isResolver()) return null;

        var container = processedSchema.getRecordType(field.getContainerTypeName());

        var previousTable = container.hasTable() ?
                container.getTable()
                : Optional.ofNullable(processedSchema.getPreviousTableObjectForObject(container)).map(RecordObjectSpecification::getTable).orElse(null);

        if (previousTable == null) {
            var targetTable = processedSchema.getRecordType(field).getTable().getName();

            return getPrimaryKeyForTable(targetTable)
                    .orElseThrow(() -> new IllegalArgumentException(
                            String.format("Code generation failed for %s.%s as the table %s must have a primary key in order to be referenced from a service.",
                                    field.getContainerTypeName(), field.getName(), targetTable)));
        }

        var tableFromFieldType = processedSchema.isRecordType(field) ? processedSchema.getRecordType(field).getTable() : null;

        String foreignKeyName;
        var primaryKeyOptional = getPrimaryKeyForTable(previousTable.getName());

        if (GeneratorConfig.alwaysUsePrimaryKeyInSplitQueries() || processedSchema.isMultiTableField(field)) {
            return primaryKeyOptional
                    .orElseThrow(() -> new IllegalArgumentException(
                            String.format("Code generation failed for %s.%s as the table %s must have a primary key in order to reference another table in %s field.",
                                    field.getContainerTypeName(), field.getName(), previousTable.getName(), SPLIT_QUERY.getName())));
        }

        if (processedSchema.isScalar(field.getTypeName()) && !field.hasFieldReferences()) {
            throw new RuntimeException("Cannot resolve reference for scalar field '" + field.getName() + "' in type '" + field.getContainerTypeName() + "'.");
        } else if (field.hasFieldReferences()) {
            var firstRef = field.getFieldReferences().stream().findFirst().get();
            Optional<String> implicitKey = firstRef.hasTable() ? findImplicitKey(previousTable.getName(), firstRef.getTable().getName()) : Optional.empty();

            if (firstRef.hasKey()) {
                foreignKeyName = firstRef.getKey().getName();
            } else if (firstRef.hasTableCondition() && implicitKey.isEmpty()) {
                return primaryKeyOptional
                        .orElseThrow(() -> new IllegalArgumentException(
                                String.format("Code generation failed for %s.%s as the table %s must have a primary key in order to reference another table without a foreign key.",
                                        field.getContainerTypeName(), field.getName(), previousTable.getName())));
            } else {
                foreignKeyName = implicitKey.stream().findFirst()
                        .orElseThrow(() -> new RuntimeException("Cannot find implicit key for field '" + field.getName() + "' in type '" + field.getContainerTypeName() + "'."));
            }
        } else {
            foreignKeyName = findImplicitKey(previousTable.getName(), (tableFromFieldType != null ? tableFromFieldType : previousTable).getName())
                    .orElseThrow(() -> new RuntimeException("Cannot find implicit key for field '" + field.getName() + "' in type '" + field.getContainerTypeName() + "'."));
        }

        var foreignKey = getForeignKey(foreignKeyName)
                .orElseThrow(() -> new RuntimeException("Cannot find key with name " + foreignKeyName));

        if (!foreignKey.getTable().getName().equalsIgnoreCase(previousTable.getName())) { // Reverse reference
            return primaryKeyOptional
                    .orElseThrow(() -> new IllegalArgumentException(
                            String.format("Code generation failed for %s.%s as the table %s must have a primary key in order to reference another table in a listed field.",
                                    field.getContainerTypeName(), field.getName(), previousTable.getName())));
        }
        return foreignKey;
    }

    /**
     * Get the Row TypeName for the key used for a resolver field
     *
     * @param field The resolver field
     * @return TypeName of the key variable
     */
    public static TypeName getKeyRowTypeName(GenerationField field, ProcessedSchema schema) {
        return findKeyForResolverField(field, schema).getRowTypeName();
    }
}
