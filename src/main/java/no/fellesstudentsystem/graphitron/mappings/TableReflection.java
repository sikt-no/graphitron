package no.fellesstudentsystem.graphitron.mappings;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import org.apache.commons.lang3.StringUtils;
import org.jooq.ForeignKey;
import org.jooq.Table;
import org.jooq.impl.TableImpl;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Helper class that takes care of any table reflection operations the code generator might require towards the jOOQ source.
 */
public class TableReflection {
    private final static Set<Field> TABLE_FIELDS = Set.of(GeneratorConfig.getGeneratedJooqTablesClass().getFields());
    private final static Map<String, Field> POSSIBLE_TABLE_FIELDS = TABLE_FIELDS.stream().collect(Collectors.toMap(Field::getName, Function.identity()));

    /**
     * @return Does the left table have exactly one reference to the right table?
     */
    public static boolean hasSingleReference(String leftTableName, String rightTableName) {
        var tableClass = GeneratorConfig.getGeneratedJooqTablesClass();
        try {
            var leftTable = (Table<?>) tableClass.getField(leftTableName).get(null);
            var rightTable = (Table<?>) tableClass.getField(rightTableName).get(null);
            var keys = leftTable.getReferencesTo(rightTable);
            return keys.size() == 1;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return false;
        }
    }

    /**
     * @return The ID name of a foreign key reference from the left table to the right table, if one exists.
     */
    public static String getQualifiedId(String leftTableName, String rightTableName) {
        var tableClass = GeneratorConfig.getGeneratedJooqTablesClass();
        try {
            var leftTable = (Table<?>) tableClass.getField(leftTableName).get(null);
            var rightTable = (Table<?>) tableClass.getField(rightTableName).get(null);
            var keys = leftTable.getReferencesTo(rightTable);
            if (keys.size() == 1) {
                var keyName = keys.get(0).getName();
                return (String) leftTable.getClass().getMethod("getQualifier", String.class).invoke(leftTable, keyName);
            }
            return null;
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    /**
     * @return Does this table exist in the generated jOOQ code?
     */
    public static boolean tableExists(String tableName) {
        return POSSIBLE_TABLE_FIELDS.containsKey(tableName);
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
     * @return Does this field have a default value in this jOOQ table? Does not work for views.
     */
    public static boolean tableFieldHasDefaultValue(String tableName, String fieldName) {
        var table = getTableObject(tableName);
        if (table.isEmpty()) {
            return false;
        }

        return Arrays
                .stream(table.get().fields())
                .filter(it -> it.getName().equalsIgnoreCase(fieldName))
                .findFirst()
                .map(value -> value.getDataType().defaulted()) // This does not work for views.
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
        var source = getTableObject(table);

        if (source.isEmpty()) {
            return Optional.empty();
        }

        return source
                .get()
                .getReferences()
                .stream()
                .map(it -> matchName(key, it))
                .filter(it -> !it.isEmpty())
                .findFirst();
    }

    private static String matchName(String key, ForeignKey<?, ?> referenceKey) {
        var unquoted = referenceKey.getQualifiedName().unquotedName().toString();
        var split = unquoted.split("\\.");
        var replace = unquoted.replace(".", "__");
        if (replace.equalsIgnoreCase(key)) {
            return uncapitalize(Arrays.stream(referenceKey.getName().split("_")).map(StringUtils::capitalize).collect(Collectors.joining()));
        }

        if (split[split.length - 1].equalsIgnoreCase(key)) {
            var splitDouble = Arrays
                    .stream(referenceKey.getName().split("__"))
                    .map(String::toLowerCase)
                    .map(it -> Arrays.stream(it.split("_")).map(StringUtils::capitalize).collect(Collectors.joining()))
                    .map(StringUtils::capitalize)
                    .collect(Collectors.joining("_"));
            return uncapitalize(splitDouble);
        }

        return "";
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

    /**
     * Find the table this foreign key points to.
     * @param keyName The name of the key to check.
     * @return The table this key points to if it exists.
     */
    public static Optional<String> getJoinTableByKey(String keyName) {
        try {
            return Optional.of(
                    ((ForeignKey<?, ?>) GeneratorConfig.getGeneratedJooqKeysClass().getField(keyName).get(null))
                            .getKey()
                            .getTable()
                            .getName()
            );
        } catch (Exception e) {
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
}
