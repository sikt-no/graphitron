package no.fellesstudentsystem.graphitron.mappings;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import org.jooq.ForeignKey;
import org.jooq.Table;
import org.jooq.impl.TableImpl;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        var table = getTablesField(tableName);
        if (table.isEmpty()) {
            return false;
        }

        try {
            return Arrays
                    .stream(((TableImpl<?>) table.get().get(null)).fields())
                    .filter(it -> it.getName().toUpperCase().equals(fieldName))
                    .findFirst()
                    .map(value -> value.getDataType().defaulted()) // This does not work for views.
                    .orElse(false);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
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
     * Search this jOOQ table for a method that represents this foreign key reference.
     * @param tableName Name of the jOOQ table.
     * @param keyName Name of the key that might have a method associated with it.
     * @return The name of a method that represents this foreign key reference if it exists.
     */
    public static Optional<String> searchTableForMethodByKey(String tableName, String keyName) {
        var field = getTablesField(tableName);
        if (field.isEmpty()) {
            return Optional.empty();
        }
        var k = keyName.replace("_", "");
        return Stream
                .of(field.get().getType().getMethods())
                .map(Method::getName)
                .filter(m -> m.replace("_", "").equalsIgnoreCase(k))
                .findFirst();
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
