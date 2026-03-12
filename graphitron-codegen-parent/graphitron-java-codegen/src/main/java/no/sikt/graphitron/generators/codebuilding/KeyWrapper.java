package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.validation.ValidationHandler;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.Key;
import org.jooq.Typed;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static no.sikt.graphitron.mappings.JavaPoetClassName.LIST;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessageAndThrow;
import static no.sikt.graphql.directives.GenerationDirective.SPLIT_QUERY;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public record KeyWrapper(Key<?> key, TypeName tableRecordTypeName) {
    public String getDTOVariableName() {
        return uncapitalize(key.getName());
    }

    public String getDTOGetterName() {
        return "get" + capitalize(key.getName());
    }

    @Deprecated
    public TypeName getRowTypeName() {
        var keyFields = key.getFields();

        if (keyFields.size() > 22) {
            addErrorMessageAndThrow("Key '%s' has more than 22 fields, which is not supported.", key.getName());
        }

        var rowOrRecordClass = ClassName.get("org.jooq", String.format("%s%d", "Row", keyFields.size()));

        return ParameterizedTypeName.get(rowOrRecordClass, keyFields.stream().map(Typed::getType).map(ClassName::get).toArray(ClassName[]::new));
    }

    /**
     * Returns the TableRecord TypeName for this key.
     */
    public TypeName getTypeName() {
        return getTypeName(false);
    }

    /**
     * Returns the TableRecord TypeName for this key, optionally wrapped in a List.
     */
    public TypeName getTypeName(boolean asList) {
        return asList ? ParameterizedTypeName.get(LIST.className, tableRecordTypeName) : tableRecordTypeName;
    }

    /**
     * Maps resolver field names to their resolved keys (foreign key or primary key).
     *
     * @param fields The fields to find keys for. This list may contain fields which are not resolvers.
     * @param schema The processed schema.
     * @return Map of field names to their resolved keys.
     */
    public static LinkedHashMap<String, KeyWrapper> getKeyMapForResolverFields(List<? extends GenerationField> fields, ProcessedSchema schema) {
        return fields.stream()
                .filter(GenerationTarget::isGeneratedWithResolver)
                .collect(Collectors.toMap(GenerationField::getName, it ->
                        getKeyForResolverFieldOrThrow(it, schema), (a, b) -> b, LinkedHashMap::new));
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
     * Finds the key used in the first step when resolving a resolver field, throwing if not found.
     *
     * @param field           The resolver field.
     * @param processedSchema The processed schema.
     * @return Wrapper for the key used in the first resolution step.
     */
    public static KeyWrapper getKeyForResolverFieldOrThrow(GenerationField field, ProcessedSchema processedSchema) {
        return getResolverKey(field, processedSchema)
                .orElseThrow(() -> new RuntimeException("Failed to find resolver key for field " + field.formatPath()));
    }
    /**
     * Finds the key used in the first resolution step if the field is a resolver field.
     */
    private static Optional<KeyWrapper> getResolverKey(GenerationField field, ProcessedSchema processedSchema) {
        if (!field.createsDataFetcher()) return Optional.empty();

        var container = processedSchema.getRecordType(field.getContainerTypeName());

        var previousTable = container.hasTable() ?
                container.getTable()
                : Optional.ofNullable(processedSchema.getPreviousTableObjectForObject(container)).map(RecordObjectSpecification::getTable).orElse(null);

        if (previousTable == null) {
            var targetTable = processedSchema.getRecordType(field).getTable().getName();
            return withPrimaryKey(
                    targetTable,
                    () -> String.format("Code generation failed for %s as the table '%s' must have a primary key in order to be referenced from a service.",
                                field.formatPath(), targetTable)
            );
        }

        var tableFromFieldType = processedSchema.isRecordType(field) ? processedSchema.getRecordType(field).getTable() : null;

        if (GeneratorConfig.alwaysUsePrimaryKeyInSplitQueries() || processedSchema.isMultiTableField(field)) {
            return withPrimaryKey(
                    previousTable.getName(),
                    () -> String.format("Code generation failed for %s as the table '%s' must have a primary key in order to reference another table in @%s field.",
                            field.formatPath(), previousTable.getName(), SPLIT_QUERY.getName())
            );
        }

        String foreignKeyName;
        if (processedSchema.isScalar(field.getTypeName()) && !field.hasFieldReferences()) {
            throw new RuntimeException("Cannot resolve reference for scalar field " + field.formatPath() + ".");
        } else if (field.hasFieldReferences()) {
            var firstRef = field.getFieldReferences().stream().findFirst().get();
            Optional<String> implicitKey = firstRef.hasTable() ? findImplicitKey(previousTable.getName(), firstRef.getTable().getName()) : Optional.empty();

            if (firstRef.hasKey()) {
                foreignKeyName = firstRef.getKey().getName();
            } else if (firstRef.hasTableCondition() && implicitKey.isEmpty()) {
                return withPrimaryKey(
                        previousTable.getName(),
                        () -> String.format("Code generation failed for %s as the table '%s' must have a primary key in order to reference another table without a foreign key.",
                                field.formatPath(), previousTable.getName())
                );
            } else {
                foreignKeyName = implicitKey.stream().findFirst()
                        .orElseThrow(() -> {
                            addErrorMessage("Cannot find implicit key for field %s.", field.formatPath());
                            return ValidationHandler.getException();
                        });
            }
        } else {
            foreignKeyName = findImplicitKey(previousTable.getName(), (tableFromFieldType != null ? tableFromFieldType : previousTable).getName())
                    .orElseThrow(() -> {
                        addErrorMessage("Cannot find implicit key for field %s.", field.formatPath());
                        return ValidationHandler.getException();
                    });
        }

        var foreignKey = getForeignKey(foreignKeyName)
                .orElseThrow(() -> {
                   addErrorMessage("Cannot find key with name '%s'.", foreignKeyName);
                   return ValidationHandler.getException();
                });

        String javaTableNameForKey = getTableJavaFieldNameByTableName(foreignKey.getTable().getName())
                .orElseThrow(() -> new RuntimeException("Cannot find jOOQ table name for table " + foreignKey.getTable().getName())); // Should never happen

        var targetTable = javaTableNameForKey.equalsIgnoreCase(previousTable.getName())
                ? foreignKey.getInverseKey().getTable()
                : foreignKey.getTable();

        return Optional.of(new KeyWrapper(foreignKey, ClassName.get(targetTable.getRecordType())));
    }

    private static Optional<KeyWrapper> withPrimaryKey(String tableJavaName, Supplier<String> noPrimaryKeyErrorMessage) {
        var primaryKey = getPrimaryKeyForTable(tableJavaName)
                .orElseThrow(() -> {
                    addErrorMessage(noPrimaryKeyErrorMessage.get());
                    return ValidationHandler.getException();
                });
        var tableRecordClass = getRecordClass(tableJavaName)
                .map(ClassName::get)
                .orElseThrow(() -> {
                    // This should never happen
                    addErrorMessage("Cannot find TableRecord class for table with name '%s'.", tableJavaName);
                    return ValidationHandler.getException();
                });
        return Optional.of(new KeyWrapper(primaryKey, tableRecordClass));
    }

    /**
     * Returns the TableRecord TypeName for the key used in the first resolution step of a resolver field.
     *
     * @param field The resolver field.
     * @return TableRecord TypeName of the resolved key.
     */
    public static TypeName getKeyTableRecordTypeName(GenerationField field, ProcessedSchema schema) {
        return getKeyForResolverFieldOrThrow(field, schema).getTypeName();
    }
}
