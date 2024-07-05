package no.fellesstudentsystem.graphitron.mappings;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.TableRelationType;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jooq.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.isFSKeyFormat;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Helper class that takes care of any table reflection operations the code generator might require towards the jOOQ source.
 */
public class TableReflection {
    private final static Map<String, Table<?>> TABLES_BY_JAVA_FIELD_NAME = getTablesByJavaFieldName();
    private final static Map<String, ForeignKey<?, ?>> FOREIGN_KEYS_BY_JAVA_FIELD_NAME = getForeignKeysByJavaFieldName();

    /**
     * @return The implicit key between these two tables.
     */
    public static Optional<String> findImplicitKey(String leftTableName, String rightTableName) {
        var leftTableOptional = getTable(leftTableName);
        if (leftTableOptional.isEmpty()) {
            return Optional.empty();
        }
        var rightTableOptional = getTable(rightTableName);
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
        var leftTable = getTable(leftTableName);
        if (leftTable.isEmpty()) {
            return TableRelationType.NONE;
        }
        var rightTable = getTable(rightTableName);
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
        return TABLES_BY_JAVA_FIELD_NAME.containsKey(tableName);
    }

    /**
     * @return Does this key exist in the generated jOOQ code?
     */
    public static boolean keyExists(String keyName) {
        return FOREIGN_KEYS_BY_JAVA_FIELD_NAME.containsKey(keyName);
    }

    /**
     * @return Does this table or key exist in the generated jOOQ code?
     */
    public static boolean tableOrKeyExists(String name) {
        return tableExists(name) || keyExists(name);
    }

    /**
     * @return Which table does this key point to?
     */
    public static Optional<String> getKeyTargetTable(String keyName) {
        return getForeignKey(keyName).map(it -> it.getKey().getTable().getName().toUpperCase());
    }

    /**
     * @return Which table does this key belong to?
     */
    public static Optional<String> getKeySourceTable(String keyName) {
        return getForeignKey(keyName).map(it -> it.getTable().getName().toUpperCase());
    }

    public static Optional<Map<TableField<?, ?>, TableField<?, ?>>> getKeyFields(JOOQMapping key) {
        if(key == null || key.getMappingName() == null) {
            return Optional.empty();
        }

        var keyName = key.getMappingName();
        var fromColumns = getForeignKey(keyName).map(Key::getFields);
        var toColumns = getForeignKey(keyName).map(ForeignKey::getKeyFields);

        if(fromColumns.isEmpty() || toColumns.isEmpty()) {
            return Optional.empty();
        }

        if(fromColumns.get().size() != toColumns.get().size()) {
            return Optional.empty();
        }
        Map<TableField<?, ?>, TableField<?, ?>> map = IntStream.range(0, fromColumns.get().size())
                .boxed()
                .collect(Collectors.toMap(fromColumns.get()::get, toColumns.get()::get));
        return Optional.of(map);
    }

    /**
     * @return Set of the names for all the fields that are set as required in the jOOQ table.
     */
    public static Set<String> getRequiredFields(String tableName) {
        return getTable(tableName)
                .map(table -> table.fieldStream()
                        .filter(field -> !field.getDataType().nullable())
                        .map(Field::getName)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    /**
     * @return Is this field nullable in the jOOQ table?.
     */
    public static Optional<Boolean> fieldIsNullable(String tableName, String fieldName) {
        return getTable(tableName)
                .map(table -> table
                        .fieldStream()
                        .filter(it -> it.getName().equalsIgnoreCase(fieldName))
                        .anyMatch(it -> it.getDataType().nullable()));
    }

    /**
     * @return Does this field have a default value in this jOOQ table? Does not work for views.
     */
    public static boolean tableFieldHasDefaultValue(String tableName, String fieldName) {
        return getTable(tableName)
                .flatMap(value -> Arrays.stream(value.fields()).filter(it -> it.getName().equalsIgnoreCase(fieldName)).findFirst())
                .map(it -> it.getDataType().defaulted()) // This does not work for views.
                .orElse(false);
    }

    /**
     * Checks if a table has an index with the specified name.
     *
     * @param tableName The name of the table to check for the index.
     * @param indexName The name of the index
     * @return Returns true if the table has an index with the same name as provided, false otherwise.
     */
    public static boolean tableHasIndex(String tableName, String indexName) {
        return getIndex(tableName, indexName).isPresent();
    }

    public static Optional<Index> getIndex(String tableName, String indexName) {
        return getTable(tableName)
                .flatMap(table -> table.getIndexes().stream()
                        .filter(index -> index.getName().equalsIgnoreCase(indexName))
                        .findFirst());
    }

    /**
     * Search this jOOQ table for a method that matches this name.
     * @param tableName Name of the jOOQ table.
     * @param name Name that might have a method associated with it.
     * @return The name of a method that matches the provided name if it exists.
     */
    public static Optional<String> searchTableForMethodWithName(String tableName, String name) {
        var adjustedName = name.replace("_", "");
        return searchTableForKeyMethodName(tableName, name)
                .or(() -> getMethodName(tableName, name))
                .or(() -> getMethodName(tableName, name.toLowerCase()))
                .or(() -> getMethodName(tableName, adjustedName));
    }

    @NotNull
    private static Optional<String> getMethodName(String tableName, String name) {
        return getMethod(tableName, name)
                .map(Method::getName);
    }

    @NotNull
    /***
     * @deprecated Denne metoden skal ikke lenger være public.
     */
    public static Optional<Method> getMethod(String tableName, String name) {
        return getTable(tableName)
                .flatMap(table -> {
                    try {
                        return Optional.of(table.getClass().getMethod(name));
                    } catch (NoSuchMethodException e) {
                        return Optional.empty();
                    }
                });
    }

    /**
     * Search this jOOQ table for a method that matches this name.
     * @param table Name of the jOOQ table.
     * @return The name of a method that matches a key between the tables if exists.
     */
    public static Optional<String> searchTableForKeyMethodName(String table, String key) {
        return getTable(table).flatMap(value -> value
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

    private static Optional<Table<?>> getTable(String name) {
        return Optional.ofNullable(TABLES_BY_JAVA_FIELD_NAME.get(name));
    }

    private static Optional<ForeignKey<?, ?>> getForeignKey(String name) {
        return Optional.ofNullable(FOREIGN_KEYS_BY_JAVA_FIELD_NAME.get(name));
    }

    /**
     * @return Set of field names for this table.
     */
    public static Set<String> getJavaFieldNamesForTable(String tableName) {
        return getTable(tableName)
                .map(table -> Arrays.stream(table.getClass().getFields())
                        .map(TableReflection::getJavaFieldName)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    private static String getFixedKeyName(ForeignKey<?, ?> key) { // TODO: Remove this hack.
        var unquoted = key.getQualifiedName().unquotedName().toString();
        if (!isFSKeyFormat()) {
            return unquoted.replace(".", "__");
        }

        var split = unquoted.split("\\.");
        return split[split.length - 1];
    }

    protected static Map<String, Table<?>> getTablesByJavaFieldName() {
        return getDefaultCatalog()
                .schemaStream()
                .flatMap(getFieldsFromSchemaClass("Tables"))
                .filter(it -> Table.class.isAssignableFrom(it.getType()))
                .collect(Collectors.toMap(
                        TableReflection::getJavaFieldName,
                        it -> (Table<?>) getJavaFieldValue(it)));
    }

    protected static Map<String, ForeignKey<?, ?>> getForeignKeysByJavaFieldName() {
        return getDefaultCatalog()
                .schemaStream()
                .flatMap(getFieldsFromSchemaClass("Keys"))
                .filter(it -> ForeignKey.class.isAssignableFrom(it.getType()))
                .collect(Collectors.toMap(
                        TableReflection::getJavaFieldName,
                        it -> (ForeignKey<?, ?>) getJavaFieldValue(it)));
    }

    private static Function<Schema, Stream<java.lang.reflect.Field>> getFieldsFromSchemaClass(String className) {
        return getClassFromSchemaPackage(className)
                .andThen(it -> Arrays.stream(it.getFields()));
    }

    @NotNull
    private static Function<Schema, Class<?>> getClassFromSchemaPackage(String className) {
        return schema -> {
            var packageName = schema.getClass().getPackageName();
            try {
                return Class.forName(packageName + "." + className);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(packageName + " did not contain a Keys class. Inconceivable.", e);
            }
        };
    }

    private static String getJavaFieldName(java.lang.reflect.Field field) {
        return field.getName();
    }

    private static Object getJavaFieldValue(java.lang.reflect.Field field) {
        try {
            return field.get(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Catalog getDefaultCatalog() {
        var generatedJooqPackage = GeneratorConfig.getGeneratedJooqPackage();

        try {
            var defaultCatalogClass = Class.forName(generatedJooqPackage + ".DefaultCatalog");
            var defaultCatalogField = defaultCatalogClass.getField("DEFAULT_CATALOG");
            return (Catalog) defaultCatalogField.get(null);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(generatedJooqPackage + " did not contain a DefaultCatalog class. This is probably a configuration error.", e);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(generatedJooqPackage + ".DefaultCatalog did not contain the DEFAULT_CATALOG field. Inconceivable.", e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Could not get " + generatedJooqPackage + ".DefaultCatalog.DEFAULT_CATALOG. Inconceivable.", e);
        }
    }
}
