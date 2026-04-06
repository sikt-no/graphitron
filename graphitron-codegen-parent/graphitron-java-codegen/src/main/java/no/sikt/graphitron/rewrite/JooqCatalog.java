package no.sikt.graphitron.rewrite;

import org.jooq.Catalog;
import org.jooq.ForeignKey;
import org.jooq.Schema;
import org.jooq.Table;

import java.lang.reflect.Field;
import java.util.Arrays;
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

    private Optional<Class<?>> keysClass(Schema schema) {
        try {
            return Optional.of(Class.forName(schema.getClass().getPackageName() + ".Keys"));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * Find a column in a table by its SQL name. Returns both the jOOQ {@link org.jooq.Field}
     * instance and its Java field name in the generated table class (e.g. {@code "FILM_ID"}).
     * Uses reflection to read the actual Java identifier name, which respects custom jOOQ
     * naming strategies rather than assuming a plain {@code toUpperCase()} transformation.
     */
    public Optional<ColumnEntry> findColumn(Table<?> table, String sqlColumnName) {
        return Arrays.stream(table.getClass().getFields())
            .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
            .map(f -> new ColumnEntry(f.getName(), (org.jooq.Field<?>) instanceFieldValue(f, table)))
            .filter(e -> sqlColumnName.equalsIgnoreCase(e.column().getName()))
            .findFirst();
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

    public record ColumnEntry(String javaName, org.jooq.Field<?> column) {}
}
