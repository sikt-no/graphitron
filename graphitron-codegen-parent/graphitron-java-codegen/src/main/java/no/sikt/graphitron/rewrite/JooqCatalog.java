package no.sikt.graphitron.rewrite;

import org.jooq.Catalog;
import org.jooq.ForeignKey;
import org.jooq.Schema;
import org.jooq.Table;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Thin wrapper around jOOQ's {@link Catalog}. Loads the catalog once via reflection
 * ({@code DefaultCatalog.DEFAULT_CATALOG}) and provides lazy lookups — no pre-built maps.
 * Each method queries the catalog on demand using the jOOQ API.
 */
public class JooqCatalog {

    private final Catalog catalog;

    public JooqCatalog(String generatedJooqPackage) {
        this.catalog = loadDefaultCatalog(generatedJooqPackage);
    }

    /**
     * Find a table by its SQL name. Returns both the {@link Table} instance and its Java field
     * name in the generated {@code Tables} class (e.g. {@code "FILM"} for {@code Tables.FILM}).
     */
    public Optional<TableEntry> findTable(String sqlName) {
        return catalog.schemaStream()
            .flatMap(schema -> tablesClass(schema).stream())
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> Table.class.isAssignableFrom(f.getType()))
            .map(f -> new TableEntry(f.getName(), (Table<?>) fieldValue(f)))
            .filter(e -> e.table().getName().equalsIgnoreCase(sqlName))
            .findFirst();
    }

    /**
     * Returns the SQL names of all tables known to the catalog, in the order they appear in the
     * generated {@code Tables} class. Used to build candidate hints in error messages.
     */
    public java.util.List<String> allTableSqlNames() {
        return catalog.schemaStream()
            .flatMap(schema -> tablesClass(schema).stream())
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> Table.class.isAssignableFrom(f.getType()))
            .map(f -> ((Table<?>) fieldValue(f)).getName())
            .toList();
    }

    /**
     * Returns the SQL constraint names of all foreign keys known to the catalog.
     * Used to build candidate hints when a {@code @reference(key:)} name cannot be resolved.
     */
    public java.util.List<String> allForeignKeySqlNames() {
        return catalog.schemaStream()
            .flatMap(schema -> schema.getTables().stream())
            .flatMap(table -> table.getReferences().stream())
            .map(fk -> fk.getName())
            .toList();
    }

    /**
     * Find a table by its jOOQ record class. Returns the table whose
     * {@link org.jooq.Table#getRecordType()} equals {@code recordClass}, or empty when no match
     * is found (e.g. the record comes from a different catalog).
     */
    public Optional<TableEntry> findTableByRecordClass(Class<?> recordClass) {
        return catalog.schemaStream()
            .flatMap(schema -> tablesClass(schema).stream())
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> Table.class.isAssignableFrom(f.getType()))
            .map(f -> new TableEntry(f.getName(), (Table<?>) fieldValue(f)))
            .filter(e -> e.table().getRecordType().equals(recordClass))
            .findFirst();
    }

    /**
     * Find a foreign key by name, searching all schemas in the catalog.
     * First tries matching by SQL constraint name (e.g. {@code "film_language_id_fkey"}),
     * then falls back to matching by the jOOQ-generated Java constant name
     * (e.g. {@code "FILM__FILM_LANGUAGE_ID_FKEY"}) via reflection on the generated
     * {@code Keys} class. Both lookups are case-insensitive.
     */
    @SuppressWarnings("unchecked")
    public Optional<ForeignKey<?, ?>> findForeignKey(String name) {
        var bySql = (Optional<ForeignKey<?, ?>>) (Optional<?>) catalog.schemaStream()
            .flatMap(schema -> schema.getTables().stream())
            .flatMap(table -> table.getReferences().stream())
            .filter(fk -> fk.getName().equalsIgnoreCase(name))
            .findFirst();
        if (bySql.isPresent()) {
            return bySql;
        }
        return (Optional<ForeignKey<?, ?>>) (Optional<?>) catalog.schemaStream()
            .flatMap(schema -> keysClass(schema).stream())
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> ForeignKey.class.isAssignableFrom(f.getType()))
            .filter(f -> f.getName().equalsIgnoreCase(name))
            .map(f -> fieldValue(f))
            .findFirst();
    }

    /**
     * Returns all foreign keys that connect {@code tableA} and {@code tableB}, in either direction.
     * A FK is included when one endpoint is {@code tableA} and the other is {@code tableB}
     * (case-insensitive). Used to resolve {@code @reference(path: [{table: "..."}])} elements
     * when no explicit FK name is given.
     */
    @SuppressWarnings("unchecked")
    public List<ForeignKey<?, ?>> findForeignKeysBetweenTables(String tableA, String tableB) {
        return (List<ForeignKey<?, ?>>) (List<?>) catalog.schemaStream()
            .flatMap(schema -> schema.getTables().stream())
            .flatMap(table -> table.getReferences().stream())
            .filter(fk -> {
                String fkSide  = fk.getTable().getName();
                String keySide = fk.getKey().getTable().getName();
                return (fkSide.equalsIgnoreCase(tableA) && keySide.equalsIgnoreCase(tableB))
                    || (fkSide.equalsIgnoreCase(tableB) && keySide.equalsIgnoreCase(tableA));
            })
            .toList();
    }

    private Optional<Class<?>> keysClass(Schema schema) {
        try {
            return Optional.of(Class.forName(schema.getClass().getPackageName() + ".Keys"));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * Return the columns of the named index on a table, in index-field order. Each column is
     * resolved via {@link #findColumn(Table, String)}. Returns empty when the index is not found
     * in the table or when any column cannot be resolved.
     */
    public Optional<java.util.List<ColumnEntry>> findIndexColumns(String tableSqlName, String indexName) {
        return findTable(tableSqlName).flatMap(te ->
            te.table().getIndexes().stream()
                .filter(idx -> idx.getName().equalsIgnoreCase(indexName))
                .findFirst()
                .map(idx -> idx.getFields().stream()
                    .map(sf -> findColumn(te.table(), sf.getName()))
                    .<ColumnEntry>flatMap(Optional::stream)
                    .toList()));
    }

    /**
     * Return the primary-key columns of a table in key-field order, or an empty list when the
     * table has no primary key or cannot be found. Each column is resolved via
     * {@link #findColumn(Table, String)}.
     */
    public java.util.List<ColumnEntry> findPkColumns(String tableSqlName) {
        return findTable(tableSqlName).map(te -> {
            var pk = te.table().getPrimaryKey();
            if (pk == null) return java.util.List.<ColumnEntry>of();
            return pk.getFields().stream()
                .map(f -> findColumn(te.table(), f.getName()))
                .flatMap(Optional::stream)
                .toList();
        }).orElse(java.util.List.of());
    }

    /**
     * Returns all SQL column names for the given table, in the order they appear in the generated
     * jOOQ table class. Returns an empty list when the table cannot be found.
     */
    public java.util.List<String> columnSqlNamesOf(String tableSqlName) {
        return findTable(tableSqlName)
            .map(te -> Arrays.stream(te.table().getClass().getFields())
                .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
                .map(f -> ((org.jooq.Field<?>) instanceFieldValue(f, te.table())).getName())
                .toList())
            .orElse(java.util.List.of());
    }

    /**
     * Returns all columns for the given table, in the order they appear in the generated jOOQ
     * table class. Each entry includes the Java field name, fully qualified column type, SQL name,
     * and nullability. Returns an empty list when the table cannot be found.
     */
    public java.util.List<ColumnEntry> allColumnsOf(String tableSqlName) {
        return findTable(tableSqlName)
            .map(te -> Arrays.stream(te.table().getClass().getFields())
                .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
                .map(f -> {
                    var col = (org.jooq.Field<?>) instanceFieldValue(f, te.table());
                    return new ColumnEntry(f.getName(), col.getType().getName(), col.getName(), col.getDataType().nullable());
                })
                .toList())
            .orElse(java.util.List.of());
    }

    /**
     * Find a column in a table by its SQL name. Returns the Java field name in the generated table
     * class (e.g. {@code "FILM_ID"}) and the fully qualified column type name. Uses reflection to
     * read the actual Java identifier name, which respects custom jOOQ naming strategies rather
     * than assuming a plain {@code toUpperCase()} transformation.
     */
    public Optional<ColumnEntry> findColumn(Table<?> table, String sqlColumnName) {
        return Arrays.stream(table.getClass().getFields())
            .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
            .map(f -> {
                var col = (org.jooq.Field<?>) instanceFieldValue(f, table);
                return new ColumnEntry(f.getName(), col.getType().getName(), col.getName(), col.getDataType().nullable());
            })
            .filter(e -> sqlColumnName.equalsIgnoreCase(e.sqlName()))
            .findFirst();
    }

    /**
     * Find a column by SQL table name and SQL column name. Delegates to
     * {@link #findTable(String)} followed by {@link #findColumn(Table, String)}.
     */
    public Optional<ColumnEntry> findColumn(String tableSqlName, String columnSqlName) {
        return findTable(tableSqlName)
            .flatMap(e -> findColumn(e.table(), columnSqlName));
    }

    private Optional<Class<?>> tablesClass(Schema schema) {
        try {
            return Optional.of(Class.forName(schema.getClass().getPackageName() + ".Tables"));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    private static Object fieldValue(Field f) {
        try {
            return f.get(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object instanceFieldValue(Field f, Object instance) {
        try {
            return f.get(instance);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Catalog loadDefaultCatalog(String generatedJooqPackage) {
        try {
            var cls = Class.forName(generatedJooqPackage + ".DefaultCatalog");
            var field = cls.getField("DEFAULT_CATALOG");
            return (Catalog) field.get(null);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                generatedJooqPackage + " did not contain a DefaultCatalog class. This is probably a configuration error.", e);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(
                "Could not access " + generatedJooqPackage + ".DefaultCatalog.DEFAULT_CATALOG.", e);
        }
    }

    public record TableEntry(String javaFieldName, Table<?> table) {}

    /**
     * Column resolution result.
     *
     * <p>{@code javaName} is the Java field name in the generated jOOQ table class
     * (e.g. {@code "FILM_ID"}). {@code columnClass} is the fully qualified Java type name of the
     * column (e.g. {@code "java.lang.Long"}). {@code sqlName} is the SQL column name used
     * internally for filtering; it is not exposed beyond the catalog. {@code nullable} reflects
     * the column's nullability as declared in the jOOQ data type.
     */
    public record ColumnEntry(String javaName, String columnClass, String sqlName, boolean nullable) {}
}
