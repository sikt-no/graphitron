package no.sikt.graphitron.mappings;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.mapping.TableRelationType;
import org.jetbrains.annotations.NotNull;
import org.jooq.*;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

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
    private final static Map<String, Table<?>> TABLE_JAVA_FIELD_NAME__TO__TABLE = getTableJavaFieldName_to_table();
    private final static Map<String, String> TABLE_NAME__TO__TABLE_JAVA_FIELD_NAME = TABLE_JAVA_FIELD_NAME__TO__TABLE.entrySet().stream()
            .collect(Collectors.toMap(it -> it.getValue().getName(), Map.Entry::getKey));
    private final static Map<String, ForeignKey<?, ?>> FK_JAVA_FIELD_NAME__TO__FK = getFkJavaFieldName_to_Fk();
    private final static Map<String, String> FK_NAME__TO__FK_JAVA_FIELD_NAME = FK_JAVA_FIELD_NAME__TO__FK.entrySet().stream()
            .collect(Collectors.toMap(it -> it.getValue().getName(), Map.Entry::getKey));

    private final static Map<String, Map<String, String>> TABLE_JAVA_FIELD_NAME__TO__FK_JAVA_FIELD_NAME__TO__PATH_JAVA_METHOD_NAME = tableJavaFieldName_to_fkJavaFieldName_to_pathJavaMethodName();

    /**
     * @return The implicit key between these two tables.
     */
    public static Optional<String> findImplicitKeyGivenTableJavaFieldNames(String leftTableJavaFieldName, String rightTableJavaFieldName) {
        var keysOptional = getForeignKeysBetweenTablesGivenTableJavaFieldNames(leftTableJavaFieldName, rightTableJavaFieldName);
        if (keysOptional.isPresent()) {
            var keys = keysOptional.get();
            if (keys.size() == 1) {
                return Optional.of(FK_NAME__TO__FK_JAVA_FIELD_NAME.get(keys.get(0).getName()));
            }
        }
        return Optional.empty();
    }

    /*
    * @return an Optional list of foreign keys between leftTable and rightTable
    * */
    public static Optional<List<? extends ForeignKey<?, ?>>> getForeignKeysBetweenTablesGivenTableJavaFieldNames(String leftTableJavaFieldName, String rightTableJavaFieldName) {
        var leftTable = getTableForTableJavaFieldName(leftTableJavaFieldName).orElse(null);
        var rightTable = getTableForTableJavaFieldName(rightTableJavaFieldName).orElse(null);
        if (leftTable == null || rightTable == null) {
            return Optional.empty();
        }
        Set<ForeignKey<?, ?>> uniqueKeys = new HashSet<>();
        uniqueKeys.addAll(leftTable.getReferencesTo(rightTable));
        uniqueKeys.addAll(rightTable.getReferencesTo(leftTable));
        return Optional.of(new ArrayList<>(uniqueKeys));
    }

    /*
    * @return the number of foreign keys between leftTable and rightTable.
    * */
    public static int getNumberOfForeignKeysBetweenTablesGivenTableJavaFieldNames(String leftTableJavaFieldName, String rightTableJavaFieldName) {
        return getForeignKeysBetweenTablesGivenTableJavaFieldNames(leftTableJavaFieldName,rightTableJavaFieldName)
                .map(List::size)
                .orElse(0);
    }

    /**
     * @return The kind of relation that is present between these tables.
     */
    public static TableRelationType inferRelationTypeBetweenTableJavaFieldNames(String leftTableJavaFieldName, String rightTableJavaFieldName, JOOQMapping preferredKey) {
        var leftTable = getTableForTableJavaFieldName(leftTableJavaFieldName);
        if (leftTable.isEmpty()) {
            return TableRelationType.NONE;
        }
        var rightTable = getTableForTableJavaFieldName(rightTableJavaFieldName);
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
            if (reverseKeysSize == 1) {
                var reverseKeyFieldName = FK_NAME__TO__FK_JAVA_FIELD_NAME.get(reverseKeys.get(0).getName());
                if (reverseKeyFieldName.equals(preferredKey.getMappingName())) {
                    return TableRelationType.REVERSE_IMPLICIT;
                }
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
    public static boolean hasSelfRelation(String tableJavaFieldName) {
        return !inferRelationTypeBetweenTableJavaFieldNames(tableJavaFieldName, tableJavaFieldName, null).equals(TableRelationType.NONE);
    }

    /**
     * NEW FS hack! Some methods do not use getId(), but getId_() methods for ID...
     */
    public static boolean recordUsesFSHack(String tableJavaFieldName) {
        return getRecordClassGivenTableJavaFieldName(tableJavaFieldName)
                .map(it -> Stream.of(it.getMethods()).anyMatch(m -> m.getName().equalsIgnoreCase("getId_")))
                .orElse(false);
    }

    /**
     * @return Does this table exist in the generated jOOQ code?
     */
    public static boolean tableJavaFieldNameExists(String tableJavaFieldName) {
        return TABLE_JAVA_FIELD_NAME__TO__TABLE.containsKey(tableJavaFieldName);
    }

    /**
     * @return Does this key exist in the generated jOOQ code?
     */
    public static boolean fkJavaFieldNameExists(String fkJavaFieldName) {
        return FK_JAVA_FIELD_NAME__TO__FK.containsKey(fkJavaFieldName);
    }

    /**
     * @return Does this table or key exist in the generated jOOQ code?
     */
    public static boolean tableOrFkJavaFieldNameExists(String javaFieldName) {
        return tableJavaFieldNameExists(javaFieldName) || fkJavaFieldNameExists(javaFieldName);
    }

    /**
     * @return Which table does this key point to?
     */
    public static Optional<String> getFkTargetTableForFkJavaFieldName(String fkJavaFieldName) {
        return getFkByFkJavaFieldName(fkJavaFieldName).map(it -> it.getKey().getTable().getName().toUpperCase());
    }

    /**
     * @return Which table does this key belong to?
     */
    public static Optional<String> getFkSourceTableForFkJavaFieldName(String fkJavaFieldName) {
        return getFkByFkJavaFieldName(fkJavaFieldName).map(it -> it.getTable().getName().toUpperCase());
    }

    /**
     * @return Set of the names for all the fields that are set as required in the jOOQ table.
     */
    public static Set<String> getRequiredFieldsForTableJavaFieldName(String tableJavaFieldName) {
        return getTableForTableJavaFieldName(tableJavaFieldName)
                .map(table -> table.fieldStream()
                        .filter(field -> !field.getDataType().nullable())
                        .map(Field::getName)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    /**
     * @return Does this field have a default value in this jOOQ table? Does not work for views.
     */
    public static boolean jooqFieldNameForTableJavaFieldNameHasDefaultValue(String tableJavaFieldName, String jooqFieldName) {
        return getTableForTableJavaFieldName(tableJavaFieldName)
                .flatMap(value -> Arrays.stream(value.fields()).filter(it -> it.getName().equalsIgnoreCase(jooqFieldName)).findFirst())
                .map(it -> it.getDataType().defaulted()) // This does not work for views.
                .orElse(false);
    }

    /**
     * Checks if a table has an index with the specified name.
     *
     * @param tableJavaFieldName The name of the table to check for the index.
     * @param jooqIndexName The name of the index
     * @return Returns true if the table has an index with the same name as provided, false otherwise.
     */
    public static boolean tableForTableJavaFieldNameHasIndex(String tableJavaFieldName, String jooqIndexName) {
        return getTableForTableJavaFieldName(tableJavaFieldName)
                .flatMap(table -> table.getIndexes().stream()
                        .filter(index -> index.getName().equalsIgnoreCase(jooqIndexName))
                        .findFirst()).isPresent();
    }

    /**
     * Search this jOOQ table for a method that matches this methodName.
     * @param tableJavaFieldName Name of the jOOQ table.
     * @param methodName Name that might have a method associated with it.
     * @return The methodName of a method that matches the provided methodName if it exists.
     */
    public static Optional<String> searchTableJavaFieldNameForMethodName(String tableJavaFieldName, String methodName) {
        return searchTableFieldNameForPathMethodNameGivenFkJavaFieldName(tableJavaFieldName, methodName)
                .or(() -> getMethodNameFromTableJavaFieldName(tableJavaFieldName, methodName));
    }

    @NotNull
    private static Optional<String> getMethodNameFromTableJavaFieldName(String tableJavaFieldName, String methodName) {
        return getMethodFromTableJavaFieldName(tableJavaFieldName, methodName)
                .map(Method::getName);
    }

    @NotNull
    private static Optional<Method> getMethodFromTableJavaFieldName(String tableJavaFieldName, String methodName) {
        return getTableForTableJavaFieldName(tableJavaFieldName)
                .flatMap(table -> Arrays.stream(table.getClass().getMethods()).filter(it -> methodName.equals(it.getName())).findFirst());
    }

    /**
     * Search this jOOQ table for a path method that matches the provided key name.
     * @param tableJavaFieldName Name of the jOOQ Table field
     * @param fkJavaFieldName   Name of the jOOQ ForeignKey
     * @return The name of a path method on the table that matches the provided key
     */
    public static Optional<String> searchTableFieldNameForPathMethodNameGivenFkJavaFieldName(String tableJavaFieldName, String fkJavaFieldName) {
        var keys = TABLE_JAVA_FIELD_NAME__TO__FK_JAVA_FIELD_NAME__TO__PATH_JAVA_METHOD_NAME.get(tableJavaFieldName);
        if (keys != null) {
            var name = keys.get(fkJavaFieldName);
            return Optional.ofNullable(name);
        }

        return Optional.empty();
    }

    public static Optional<Table<?>> getTableForTableJavaFieldName(String tableJavafieldName) {
        return Optional.ofNullable(TABLE_JAVA_FIELD_NAME__TO__TABLE.get(tableJavafieldName));
    }

    public static Optional<ForeignKey<?, ?>> getFkByFkJavaFieldName(String fkJavafieldName) {
        return Optional.ofNullable(FK_JAVA_FIELD_NAME__TO__FK.get(fkJavafieldName));
    }

    /**
     * @return Set of field names for this table.
     */
    public static Set<String> getJavaFieldNamesForTableJavaFieldName(String tableJavaFieldName) {
        return getTableForTableJavaFieldName(tableJavaFieldName)
                .map(table -> Arrays.stream(table.getClass().getFields())
                        .map(field -> field.getName())
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    public static List<String> getJavaFieldNamesForKeyInTableJavaFieldName(String tableJavaFieldName, Key<?> key) {
        return key.getFields()
                .stream()
                .map(keyField ->
                        javaFieldNameForJooqFieldNameInTableJavaFieldName(tableJavaFieldName, keyField.getName())
                                .orElseThrow())
                .toList();
    }

    public static Optional<String> javaFieldNameForJooqFieldNameInTableJavaFieldName(String tableJavaFieldName, String jooqFieldName) {
        return getJavaFieldNamesForTableJavaFieldName(tableJavaFieldName)
                .stream()
                .filter(tableFieldName -> tableFieldName.equalsIgnoreCase(jooqFieldName))
                .findFirst();
    }

    public static Set<Class<?>> getClassFromAllJooqSchemaPackages(String className) {
        return getDefaultCatalog()
                .schemaStream()
                .map(getClassFromJooqSchemaPackage(className))
                .collect(Collectors.toSet());
    }

    protected static Map<String, Table<?>> getTableJavaFieldName_to_table() {
        return getDefaultCatalog()
                .schemaStream()
                .flatMap(getFieldsFromJooqSchemaClass("Tables"))
                .filter(it -> Table.class.isAssignableFrom(it.getType()))
                .collect(Collectors.toMap(
                        field -> field.getName(),
                        it -> (Table<?>) getJavaFieldValue(it)));
    }

    protected static Map<String, ForeignKey<?, ?>> getFkJavaFieldName_to_Fk() {
        return getDefaultCatalog()
                .schemaStream()
                .flatMap(getFieldsFromJooqSchemaClass("Keys"))
                .filter(it -> ForeignKey.class.isAssignableFrom(it.getType()))
                .collect(Collectors.toMap(
                        field -> field.getName(),
                        it -> (ForeignKey<?, ?>) getJavaFieldValue(it)));
    }

    /**
     * @return Map containing all the foreign key references for any table and the corresponding method names to call them.
     */
    private static Map<String, Map<String, String>> tableJavaFieldName_to_fkJavaFieldName_to_pathJavaMethodName() {
        return TableReflection.TABLE_JAVA_FIELD_NAME__TO__TABLE
                .values()
                .stream()
                .collect(Collectors.toMap(table -> TABLE_NAME__TO__TABLE_JAVA_FIELD_NAME.get(table.getName()), TableReflection::keyFieldNameToPathMethodName));
    }

    /**
     * @param table Table class to find paths for.
     * @return Map containing all the foreign key references for the table and the corresponding method names to call them.
     */
    private static Map<String, String> keyFieldNameToPathMethodName(Table<?> table) {
        return Arrays
                .stream(table.getClass().getMethods())
                .filter(it -> Path.class.isAssignableFrom(it.getReturnType()))
                .collect(Collectors.toMap(method -> getKeyFieldNameForPathMethod(table, method), Method::getName));
    }

    /**
     * @param method Method that can be used to implicitly join another table with a FK.
     * @param table Table class to which the method belongs to.
     * @return The jOOQ name of the key corresponding to the method in the table class.
     */
    private static String getKeyFieldNameForPathMethod(Table<?> table, Method method) {
        try {
            var path = (Path<?>) method.invoke(table);
            var childKey = (ForeignKey<?, ?>) getJooqFieldFromTableJavaFieldName(path, "childPath");
            var parentKey = (InverseForeignKey<?, ?>) getJooqFieldFromTableJavaFieldName(path, "parentPath");
            var key = childKey != null ? childKey : parentKey.getForeignKey();
            return FK_NAME__TO__FK_JAVA_FIELD_NAME.get(key.getName());
        } catch (IllegalAccessException | NoSuchFieldException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object getJooqFieldFromTableJavaFieldName(Path<?> path, String name) throws NoSuchFieldException, IllegalAccessException {
        var childKeyField = TableImpl.class.getDeclaredField(name);
        childKeyField.setAccessible(true);
        return childKeyField.get(path);
    }

    public static Optional<Field<?>> getJooqFieldFromTableJavaFieldName(String tableJavaFieldName, String jooqFieldName) {
        return getTableForTableJavaFieldName(tableJavaFieldName)
                .flatMap(table -> Arrays.stream(table.fields())
                        .filter(field -> field.getName().equalsIgnoreCase(jooqFieldName))
                        .findFirst()
                );
    }

    public static Optional<Class<?>> getFieldTypeFromTableJavaFieldName(String tableJavaFieldName, String jooqFieldName) {
        return getJooqFieldFromTableJavaFieldName(tableJavaFieldName, jooqFieldName).map(Typed::getType);
    }

    public static boolean jooqFieldInTableJavaFieldNameIsClob(String tableJavafieldName, String jooqFieldName) {
        return getJooqFieldFromTableJavaFieldName(tableJavafieldName, jooqFieldName)
                .flatMap(it -> Optional.ofNullable(it.getDataType().getSQLDataType()))
                .map(it -> it.equals(SQLDataType.CLOB))
                .orElse(false);
    }

    private static Function<Schema, Stream<java.lang.reflect.Field>> getFieldsFromJooqSchemaClass(String className) {
        return getClassFromJooqSchemaPackage(className)
                .andThen(it -> Arrays.stream(it.getFields()));
    }

    @NotNull
    private static Function<Schema, Class<?>> getClassFromJooqSchemaPackage(String className) {
        return schema -> {
            var packageName = schema.getClass().getPackageName();
            try {
                return Class.forName(packageName + "." + className);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(packageName + " did not contain a " + className + " class. Inconceivable.", e);
            }
        };
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

    public static Optional<Class<?>> getTableClassGivenTableJavaFieldName(String tableJavaFieldName) {
        return getTableForTableJavaFieldName(tableJavaFieldName).map(Object::getClass);
    }

    public static Optional<Class<?>> getRecordClassGivenTableJavaFieldName(String tableJavaFieldName) {
        return getTableForTableJavaFieldName(tableJavaFieldName).map(Table::getRecordType);
    }

    public static boolean tableJavaFieldNameHasPrimaryKey(String tableJavaFieldName) {
        return getPrimaryKeyForTableJavaFieldName(tableJavaFieldName).isPresent();
    }

    public static Optional<? extends UniqueKey<?>> getPrimaryKeyForTableJavaFieldName(String tableJavaFieldName) {
        return getTableForTableJavaFieldName(tableJavaFieldName).map(Table::getPrimaryKey).stream().findFirst();
    }

    public static Optional<List<? extends UniqueKey<?>>> getUniqueKeysForTableJavaFieldName(String tableJavaFieldName) {
        return getTableForTableJavaFieldName(tableJavaFieldName).map(Table::getUniqueKeys);
    }

    public static Set<? extends UniqueKey<?>> getPrimaryAndUniqueKeysForTableJavaFieldName(String tableJavaFieldName) {
        var allKeys = new HashSet<UniqueKey<?>>();
        getPrimaryKeyForTableJavaFieldName(tableJavaFieldName).map(allKeys::add);
        getUniqueKeysForTableJavaFieldName(tableJavaFieldName).map(allKeys::addAll);
        return allKeys;
    }

    public static Optional<? extends UniqueKey<?>> getPrimaryOrUniqueKeyMatchingColumnNamesForTableJavaFieldName(String tableJavaFieldName, List<String> columnNames) {
        return getTableForTableJavaFieldName(tableJavaFieldName)
                .flatMap(value ->
                        Stream.concat(value.getUniqueKeys().stream(), Stream.of(value.getPrimaryKey()))
                                .filter(key -> key.getFields().size() == columnNames.size()
                                        && columnNames.stream().allMatch(idField -> key.getFields().stream().anyMatch(keyField -> keyField.getName().equalsIgnoreCase(idField))))
                                .findFirst()
                );
    }

    public static Optional<Method> getMethodFromReferenceClassForTableJavaFieldName(String referenceClassName, String tableJavaFieldName, String methodName) {
        try {
        Class<?> clazz = Class.forName(referenceClassName);
        Optional<Class<?>> tableClass = getTableClassGivenTableJavaFieldName(tableJavaFieldName);
        if (tableClass.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(clazz.getMethod(methodName, tableClass.get()));
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return Optional.empty();
        }
    }
}
