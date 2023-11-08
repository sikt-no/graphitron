package no.fellesstudentsystem.graphitron.mappings;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.TableRelationType;
import org.apache.commons.lang3.StringUtils;
import org.jooq.ForeignKey;
import org.jooq.Table;
import org.jooq.impl.TableImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.isFSKeyFormat;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Helper class that takes care of any table reflection operations the code generator might require towards the jOOQ source.
 */
public class TableReflection {
    private final static Set<Field> TABLE_FIELDS = Set.of(GeneratorConfig.getGeneratedJooqTablesClass().getFields());
    private final static Map<String, Field> POSSIBLE_TABLE_FIELDS = TABLE_FIELDS.stream().collect(Collectors.toMap(Field::getName, Function.identity()));
    private final static Set<Field> KEY_FIELDS = Set.of(GeneratorConfig.getGeneratedJooqKeysClass().getFields());
    private final static Map<String, Field> POSSIBLE_KEY_FIELDS = KEY_FIELDS.stream().collect(Collectors.toMap(Field::getName, Function.identity()));

    /**
     * @return The implicit key between these two tables.
     */
    public static Optional<String> findImplicitKey(String leftTableName, String rightTableName) {
        var leftTableOptional = getTableObject(leftTableName);
        if (leftTableOptional.isEmpty()) {
            return Optional.empty();
        }
        var rightTableOptional = getTableObject(rightTableName);
        if (rightTableOptional.isEmpty()) {
            return Optional.empty();
        }
        var leftTable = leftTableOptional.get();
        var rightTable = rightTableOptional.get();

        var keys = leftTable.getReferencesTo(rightTable);
        if (keys.size() == 1) {
            return Optional.of(getFixedKeyName(keys.get(0)));
        }
        var reverseKeys = rightTable.getReferencesTo(leftTable);
        if (reverseKeys.size() == 1) {
            return Optional.of(getFixedKeyName(reverseKeys.get(0)));
        }
        return Optional.empty();
    }

    /**
     * @return The kind of relation that is present between these tables.
     */
    public static TableRelationType inferRelationType(String leftTableName, String rightTableName, JOOQMapping preferredKey) {
        var leftTable = getTableObject(leftTableName);
        if (leftTable.isEmpty()) {
            return TableRelationType.NONE;
        }
        var rightTable = getTableObject(rightTableName);
        if (rightTable.isEmpty()) {
            return TableRelationType.NONE;
        }

        var keysSize = leftTable.get().getReferencesTo(rightTable.get()).size();
        var reverseKeys = rightTable.get().getReferencesTo(leftTable.get());
        var reverseKeysSize = reverseKeys.size();

        // If keys should be prioritized, reverse keys are checked before normal implicit joins.
        if (preferredKey != null) {
            if (keysSize > 1) {
                return TableRelationType.KEY;
            }
            if (reverseKeysSize > 1) {
                return TableRelationType.REVERSE_KEY;
            }

            // The unlikely case where there are single references both ways, and for some reason the reverse reference is preferred.
            if (reverseKeysSize == 1 && matchName(preferredKey.getMappingName(), reverseKeys.get(0)).isPresent()) {
                return TableRelationType.REVERSE_IMPLICIT;
            }
        }

        // Normally, references the "right" way are prioritized.
        if (keysSize == 1) {
            return TableRelationType.IMPLICIT;
        }
        if (keysSize > 1) {
            return TableRelationType.KEY;
        }

        if (reverseKeysSize == 1) {
            return TableRelationType.REVERSE_IMPLICIT;
        }
        if (reverseKeysSize > 1) {
            return TableRelationType.REVERSE_KEY;
        }
        return TableRelationType.NONE;
    }

    /**
     * @return Does this table have any references to itself?
     */
    public static boolean hasSelfRelation(String tableName) {
        return !inferRelationType(tableName, tableName, null).equals(TableRelationType.NONE);
    }

    /**
     * @return Does this table exist in the generated jOOQ code?
     */
    public static boolean tableExists(String tableName) {
        return POSSIBLE_TABLE_FIELDS.containsKey(tableName);
    }

    /**
     * @return Does this key exist in the generated jOOQ code?
     */
    public static boolean keyExists(String keyName) {
        return POSSIBLE_KEY_FIELDS.containsKey(keyName);
    }

    /**
     * @return Does this table or key exist in the generated jOOQ code?
     */
    public static boolean tableOrKeyExists(String name) {
        return POSSIBLE_KEY_FIELDS.containsKey(name) || POSSIBLE_TABLE_FIELDS.containsKey(name);
    }

    /**
     * @return Which table does this key point to?
     */
    public static Optional<String> getKeyTargetTable(String keyName) {
        return getKeyObject(keyName).map(it -> it.getKey().getTable().getName().toUpperCase());
    }

    /**
     * @return Which table does this key belong to?
     */
    public static Optional<String> getKeySourceTable(String keyName) {
        return getKeyObject(keyName).map(it -> it.getTable().getName().toUpperCase());
    }

    /**
     * @return Set of the names for all the fields that are set as required in the jOOQ table.
     */
    public static Set<String> getRequiredFields(String tableName) {
        var field = getTablesField(tableName);
        if (field.isEmpty()) {
            return Set.of();
        }

        try {
            return Arrays
                    .stream(((TableImpl<?>) field.get().get(null)).fields()) // 'Tables' contains only records.
                    .filter(it -> !it.getDataType().nullable())
                    .map(org.jooq.Field::getName)
                    .collect(Collectors.toSet());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return Is this field nullable in the jOOQ table?.
     */
    public static Optional<Boolean> fieldIsNullable(String tableName, String fieldName) {
        var field = getTablesField(tableName);
        if (field.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                    Arrays
                            .stream(((TableImpl<?>) field.get().get(null)).fields())
                            .filter(it -> it.getName().equalsIgnoreCase(fieldName))
                            .anyMatch(it -> it.getDataType().nullable())
            );
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return Does this field have a default value in this jOOQ table? Does not work for views.
     */
    public static boolean tableFieldHasDefaultValue(String tableName, String fieldName) {
        return getTableObject(tableName)
                .flatMap(value -> Arrays.stream(value.fields()).filter(it -> it.getName().equalsIgnoreCase(fieldName)).findFirst())
                .map(it -> it.getDataType().defaulted()) // This does not work for views.
                .orElse(false);
    }

    /**
     * @return Does this jOOQ table contain this method name?
     */
    public static boolean tableHasMethod(String tableName, String methodName) {
        return getTablesField(tableName)
                .map(value -> Stream.of(value.getType().getMethods()).map(Method::getName).anyMatch(m -> m.equals(methodName)))
                .orElse(false);
    }

    /**
     * Search this jOOQ table for a method that matches this name.
     * @param tableName Name of the jOOQ table.
     * @param name Name that might have a method associated with it.
     * @return The name of a method that matches the provided name if it exists.
     */
    public static Optional<String> searchTableForMethodWithName(String tableName, String name) {
        var keyMethod = searchTableForKeyMethodName(tableName, name);
        if (keyMethod.isPresent()) {
            return keyMethod;
        }

        var field = getTablesField(tableName);
        if (field.isEmpty()) {
            return Optional.empty();
        }

        var adjustedName = name.replace("_", "");
        return Stream
                .of(field.get().getType().getMethods())
                .map(Method::getName)
                .filter(m -> m.replace("_", "").equalsIgnoreCase(adjustedName))
                .findFirst();
    }

    /**
     * Search this jOOQ table for a method that matches this name.
     * @param table Name of the jOOQ table.
     * @return The name of a method that matches a key between the tables if exists.
     */
    public static Optional<String> searchTableForKeyMethodName(String table, String key) {
        return getTableObject(table).flatMap(value -> value
                .getReferences()
                .stream()
                .map(it -> matchName(key, it))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
        );
    }

    private static Optional<String> matchName(String key, ForeignKey<?, ?> referenceKey) {
        if (!getFixedKeyName(referenceKey).equalsIgnoreCase(key)) {
            return Optional.empty();
        }

        if (!isFSKeyFormat()) { // TODO: Remove this hack.
            return Optional.of(uncapitalize(Arrays.stream(referenceKey.getName().split("_")).map(StringUtils::capitalize).collect(Collectors.joining())));
        }

        var splitDouble = Arrays
                .stream(referenceKey.getName().split("__"))
                .map(String::toLowerCase)
                .map(it -> Arrays.stream(it.split("_")).map(StringUtils::capitalize).collect(Collectors.joining()))
                .map(StringUtils::capitalize)
                .collect(Collectors.joining("_"));
        return Optional.of(uncapitalize(splitDouble));
    }

    private static Optional<Table<?>> getTableObject(String table) {
        var field = getTablesField(table);
        if (field.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(((Table<?>) field.get().get(null)));
        } catch (IllegalAccessException e) {
            return Optional.empty();
        }
    }

    private static Table<?> getTableObjectOrException(String table) {
        var field = getTablesField(table);
        if (field.isEmpty()) {
            throw new IllegalArgumentException("Table " + table + " does not exist.");
        }

        try {
            return (Table<?>) field.get().get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Table " + table + " does not exist.");
        }
    }

    private static Optional<ForeignKey<?, ?>> getKeyObject(String key) {
        var field = getKeysField(key);
        if (field.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(((ForeignKey<?, ?>) field.get().get(null)));
        } catch (IllegalAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * @return Set of field names for this table.
     */
    public static Set<String> getFieldNamesForTable(String tableName) {
        return getTablesField(tableName)
                .map(value -> Stream.of(value.getType().getFields()).map(Field::getName).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    /**
     * @return Find this table as a field in jOOQs Tables class through reflection.
     */
    public static Optional<Field> getTablesField(String tableName) {
        if (!tableExists(tableName)) {
            return Optional.empty();
        }

        return Optional.of(POSSIBLE_TABLE_FIELDS.get(tableName));
    }

    /**
     * @return Find this key as a field in jOOQs Keys class through reflection.
     */
    public static Optional<Field> getKeysField(String keyName) {
        if (!keyExists(keyName)) {
            return Optional.empty();
        }

        return Optional.of(POSSIBLE_KEY_FIELDS.get(keyName));
    }

    private static String getFixedKeyName(ForeignKey<?, ?> key) { // TODO: Remove this hack.
        var unquoted = key.getQualifiedName().unquotedName().toString();
        if (!isFSKeyFormat()) {
            return unquoted.replace(".", "__");
        }

        var split = unquoted.split("\\.");
        return split[split.length - 1];
    }
}
