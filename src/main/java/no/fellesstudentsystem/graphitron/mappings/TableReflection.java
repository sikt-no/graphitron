package no.fellesstudentsystem.graphitron.mappings;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
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
    private final static Map<String, Field> POSSIBLE_TABLE_FIELDS = TABLE_FIELDS.stream().collect(Collectors.toMap(Field::getName, Function.identity()));

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
        return POSSIBLE_TABLE_FIELDS.containsKey(tableName);
    }

    public static Set<String> getRequiredFields(String tableName) {
        try {
            var field = getField(tableName);
            if (field.isEmpty()) {
                return Set.of();
            }
            return Arrays
                    .stream(((TableImpl<?>) field.get().get(null)).fields()) // 'Tables' contains only records.
                    .filter(it -> !it.getDataType().nullable())
                    .map(org.jooq.Field::getName)
                    .collect(Collectors.toSet());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean tableHasMethod(String tableName, String methodName) {
        return getField(tableName)
                .map(value -> Stream.of(value.getType().getMethods()).map(Method::getName).anyMatch(m -> m.equals(methodName)))
                .orElse(false);
    }

    public static Optional<String> searchTableForMethodByKey(String tableName, String keyName) {
        var field = getField(tableName);
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

    public static Optional<String> getJoinTableByKey(String keyName) {
        try {
            return Optional.of(((ForeignKey<?, ?>) KEYS_CLASS.getField(keyName).get(null)).getKey().getTable().getName());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static Set<String> getFieldNamesForTable(String tableName) {
        return getField(tableName)
                .map(value -> Stream.of(value.getType().getFields()).map(Field::getName).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    public static Optional<Field> getField(String tableName) {
        if (!tableExists(tableName)) {
            return Optional.empty();
        }

        return Optional.of(POSSIBLE_TABLE_FIELDS.get(tableName));
    }
}
