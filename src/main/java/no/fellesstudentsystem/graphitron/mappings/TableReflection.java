package no.fellesstudentsystem.graphitron.mappings;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import org.jooq.ForeignKey;
import org.jooq.Table;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper class that takes care of any table reflection operations the code generator might require towards the jOOQ source.
 */
public class TableReflection {
    public static final Class<?> TABLES_CLASS, KEYS_CLASS;

    static {
        try {
            TABLES_CLASS = Class.forName(GeneratorConfig.getGeneratedJooqTablesPackage());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Unable to find jOOQ generated tables class. ", e);
        }
        try {
            KEYS_CLASS = Class.forName(GeneratorConfig.getGeneratedJooqKeysPackage());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Unable to find jOOQ generated keys class. ", e);
        }
    }

    private final static Set<Field> TABLE_FIELDS = Set.of(TABLES_CLASS.getFields());
    private final static Set<String> POSSIBLE_TABLE_NAMES = TABLE_FIELDS.stream().map(Field::getName).collect(Collectors.toSet());

    public static boolean hasSingleReference(String leftTableName, String rightTableName) {
        try {
            var leftTable = (Table<?>) TABLES_CLASS.getField(leftTableName).get(null);
            var rightTable = (Table<?>) TABLES_CLASS.getField(rightTableName).get(null);
            var keys = leftTable.getReferencesTo(rightTable);
            return keys.size() == 1;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return false;
        }
    }

    public static String getQualifiedId(String leftTableName, String rightTableName) {
        try {
            var leftTable = (Table<?>) TABLES_CLASS.getField(leftTableName).get(null);
            var rightTable = (Table<?>) TABLES_CLASS.getField(rightTableName).get(null);
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

    public static boolean tableExists(String tableName) {
        return POSSIBLE_TABLE_NAMES.contains(tableName);
    }

    public static boolean tableHasMethod(String tableName, String methodName) {
        return Stream.of(getField(tableName).getType().getMethods())
                .map(Method::getName)
                .anyMatch(m -> m.equals(methodName));
    }

    public static Optional<String> searchTableForMethodByKey(String tableName, String keyName) {
        var k = keyName.replace("_", "");
        return Stream
                .of(getField(tableName).getType().getMethods())
                .map(Method::getName)
                .filter(m -> m.replace("_", "").equalsIgnoreCase(k))
                .findFirst();
    }

    public static Optional<String> getJoinTableByKey(String keyName) {
        try {
            return Optional.of(((ForeignKey<?, ?>) KEYS_CLASS.getField(keyName).get(null)).getKey().getTable().getName());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static Set<String> getFieldNamesForTable(String tableName) {
        return Stream.of(getField(tableName).getType().getFields())
                .map(Field::getName)
                .collect(Collectors.toSet());
    }

    private static Field getField(String tableName) {
        return TABLE_FIELDS
                .stream()
                .filter(f -> f.getName().equals(tableName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No table with the name '" + tableName + "' exists."));
    }
}
