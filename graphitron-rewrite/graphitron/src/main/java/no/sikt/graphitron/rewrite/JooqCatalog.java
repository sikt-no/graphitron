package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ColumnRef;
import org.jooq.Catalog;
import org.jooq.ForeignKey;
import org.jooq.Schema;
import org.jooq.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin wrapper around jOOQ's {@link Catalog}. Loads the catalog once via reflection
 * ({@code DefaultCatalog.DEFAULT_CATALOG}) and provides lazy lookups — no pre-built maps.
 * Each method queries the catalog on demand using the jOOQ API.
 *
 * <p>When the generated jOOQ package is not on the classpath (e.g. in unit tests that do not
 * depend on jOOQ codegen output), all lookup methods return empty/empty-list rather than
 * throwing. A warning is logged at construction time so misconfiguration is visible in
 * production logs.
 */
public class JooqCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(JooqCatalog.class);

    private final Catalog catalog;
    private final Map<String, NodeIdMetadataLookup> metadataCache = new ConcurrentHashMap<>();

    public JooqCatalog(String generatedJooqPackage) {
        this.catalog = loadDefaultCatalog(generatedJooqPackage);
    }

    /**
     * Find a table by its SQL name. Returns both the {@link Table} instance and its Java field
     * name in the generated {@code Tables} class (e.g. {@code "FILM"} for {@code Tables.FILM}).
     */
    public Optional<TableEntry> findTable(String sqlName) {
        if (catalog == null) return Optional.empty();
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
        if (catalog == null) return java.util.List.of();
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
        if (catalog == null) return java.util.List.of();
        return catalog.schemaStream()
            .flatMap(schema -> schema.getTables().stream())
            .flatMap(table -> table.getReferences().stream())
            .map(fk -> fk.getName())
            .toList();
    }

    /**
     * Returns the jOOQ record class for the table with the given SQL name, or empty when the
     * table cannot be found or the catalog is unavailable. The returned class is whatever
     * {@link org.jooq.Table#getRecordType()} returns for the matching table — typically the
     * generated {@code *Record} class.
     */
    public Optional<Class<?>> findRecordClass(String tableSqlName) {
        return findTable(tableSqlName).map(e -> e.table().getRecordType());
    }

    /**
     * Find a table by its jOOQ record class. Returns the table whose
     * {@link org.jooq.Table#getRecordType()} equals {@code recordClass}, or empty when no match
     * is found (e.g. the record comes from a different catalog).
     */
    public Optional<TableEntry> findTableByRecordClass(Class<?> recordClass) {
        if (catalog == null) return Optional.empty();
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
        if (catalog == null) return Optional.empty();
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
     * Returns the Java constant name (e.g. {@code "FK_FILM__FILM_LANGUAGE_ID_FKEY"}) of a foreign
     * key in the generated {@code Keys} class, given the SQL constraint name
     * (e.g. {@code "film_language_id_fkey"}). Used at build time to emit
     * {@code Keys.FK_...} references in generated code.
     *
     * <p>Returns empty when the catalog or Keys class is not available (e.g. unit tests that do
     * not depend on jOOQ codegen output) or when no matching key is found.
     */
    public Optional<String> fkJavaConstantName(String sqlConstraintName) {
        if (catalog == null) return Optional.empty();
        return catalog.schemaStream()
            .flatMap(schema -> keysClass(schema).stream())
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> ForeignKey.class.isAssignableFrom(f.getType()))
            .filter(f -> ((ForeignKey<?, ?>) fieldValue(f)).getName().equalsIgnoreCase(sqlConstraintName))
            .map(Field::getName)
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
        if (catalog == null) return List.of();
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
     * Probe for KjerneJooqGenerator's node-identity metadata on the table class with the given
     * SQL name. Reads two static fields via reflection:
     *
     * <ul>
     *   <li>{@code public static final String __NODE_TYPE_ID} — the {@code typeId} that identifies
     *       the node type in encoded IDs (same value a consumer would write as
     *       {@code @node(typeId:)} in SDL for the same table).</li>
     *   <li>{@code public static final Field<?>[] __NODE_KEY_COLUMNS} — the underlying
     *       {@link org.jooq.Field} references in positional order. Each is resolved to a
     *       {@link ColumnRef} via the table's column set; any unresolved entry fails the probe.</li>
     * </ul>
     *
     * <p>Returns {@link Optional#empty()} when:
     * <ul>
     *   <li>the catalog is unavailable or the table cannot be found;</li>
     *   <li>either constant is absent on the table class;</li>
     *   <li>the constants fail the sanity checks: {@code __NODE_TYPE_ID} non-null and non-empty,
     *       {@code __NODE_KEY_COLUMNS} non-null, non-empty, and every entry resolvable to a column
     *       on the same table. Malformed metadata logs a warning keyed on the table SQL name; the
     *       classifier boundary surfaces this as an {@code UnclassifiedType} once the probe is
     *       consumed there.</li>
     * </ul>
     *
     * <p>Consumer-side: {@code NodeIdStrategy.createId(typeId, keyFields)} and
     * {@code NodeIdStrategy.hasIds(typeId, ids, keyFields)} both depend on the positional order of
     * {@code __NODE_KEY_COLUMNS} — reordering between releases would re-encode new IDs in a different
     * order than decoded IDs produced pre-upgrade, and {@code hasIds} would fail to match. The
     * probe treats the order as opaque but stable.
     */
    public Optional<NodeIdMetadata> nodeIdMetadata(String tableSqlName) {
        return lookup(tableSqlName) instanceof NodeIdMetadataLookup.Present p
            ? Optional.of(p.metadata())
            : Optional.empty();
    }

    /**
     * Sibling of {@link #nodeIdMetadata(String)} that surfaces malformed-metadata reasons at the
     * classifier boundary. Returns the reason string (without the {@code "KjerneJooqGenerator
     * metadata on table 'X' is malformed: "} prefix — the caller prepends it) when the constants
     * are present but fail validation; empty when the constants are absent or well-formed.
     *
     * <p>Classifier usage: call this first — if present, fail the type as {@code UnclassifiedType}
     * with the prefixed message; otherwise call {@link #nodeIdMetadata(String)} to get the
     * synthesized values. Results share a cache so reflection runs once per table per build.
     */
    public Optional<String> nodeIdMetadataDiagnostic(String tableSqlName) {
        return lookup(tableSqlName) instanceof NodeIdMetadataLookup.Malformed m
            ? Optional.of(m.reason())
            : Optional.empty();
    }

    private NodeIdMetadataLookup lookup(String tableSqlName) {
        return metadataCache.computeIfAbsent(tableSqlName, this::doLookup);
    }

    private NodeIdMetadataLookup doLookup(String tableSqlName) {
        return findTable(tableSqlName).<NodeIdMetadataLookup>map(te -> {
            Class<?> tableClass = te.table().getClass();
            Object typeIdRaw;
            Object keyColumnsRaw;
            try {
                typeIdRaw = tableClass.getField("__NODE_TYPE_ID").get(null);
                keyColumnsRaw = tableClass.getField("__NODE_KEY_COLUMNS").get(null);
            } catch (NoSuchFieldException e) {
                return new NodeIdMetadataLookup.Absent();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            return validateLookup(tableSqlName, typeIdRaw, keyColumnsRaw,
                name -> findColumn(te.table(), name));
        }).orElseGet(NodeIdMetadataLookup.Absent::new);
    }

    /**
     * Validation half, factored so tests can exercise each malformed-metadata branch by passing
     * synthetic raw values without having to swap {@code static final} fields on a real table
     * class. Preserved signature — wraps the three-state {@link #validateLookup}.
     */
    static Optional<NodeIdMetadata> validateNodeIdMetadata(
            String tableSqlName,
            Object typeIdRaw,
            Object keyColumnsRaw,
            java.util.function.Function<String, Optional<ColumnEntry>> columnLookup) {
        return validateLookup(tableSqlName, typeIdRaw, keyColumnsRaw, columnLookup)
                instanceof NodeIdMetadataLookup.Present p
            ? Optional.of(p.metadata())
            : Optional.empty();
    }

    private static NodeIdMetadataLookup validateLookup(
            String tableSqlName,
            Object typeIdRaw,
            Object keyColumnsRaw,
            java.util.function.Function<String, Optional<ColumnEntry>> columnLookup) {
        if (!(typeIdRaw instanceof String typeId) || typeId.isEmpty()) {
            return malformed(tableSqlName,
                "__NODE_TYPE_ID must be a non-empty String (got: " + typeIdRaw + ")");
        }
        if (!(keyColumnsRaw instanceof org.jooq.Field<?>[] keyColumnFields) || keyColumnFields.length == 0) {
            return malformed(tableSqlName,
                "__NODE_KEY_COLUMNS must be a non-empty Field<?>[] (got: " + keyColumnsRaw + ")");
        }
        var resolved = new ArrayList<ColumnRef>(keyColumnFields.length);
        for (int i = 0; i < keyColumnFields.length; i++) {
            var f = keyColumnFields[i];
            if (f == null) {
                return malformed(tableSqlName, "__NODE_KEY_COLUMNS[" + i + "] is null");
            }
            Optional<ColumnEntry> col = columnLookup.apply(f.getName());
            if (col.isEmpty()) {
                return malformed(tableSqlName,
                    "__NODE_KEY_COLUMNS[" + i + "] references column '" + f.getName()
                    + "' which does not belong to this table");
            }
            var e = col.get();
            resolved.add(new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()));
        }
        return new NodeIdMetadataLookup.Present(new NodeIdMetadata(typeId, List.copyOf(resolved)));
    }

    private static NodeIdMetadataLookup.Malformed malformed(String tableSqlName, String reason) {
        LOGGER.warn("KjerneJooqGenerator metadata on table '{}' is malformed: {}", tableSqlName, reason);
        return new NodeIdMetadataLookup.Malformed(reason);
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
     * Returns all Java field names for the given table, in the order they appear in the generated
     * jOOQ table class. Use this for error hints where the schema author is expected to supply
     * a Java field name (e.g. {@code @field(name: "FILM_ID")}). Returns an empty list when the
     * table cannot be found.
     */
    public java.util.List<String> columnJavaNamesOf(String tableSqlName) {
        return findTable(tableSqlName)
            .map(te -> Arrays.stream(te.table().getClass().getFields())
                .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
                .map(f -> f.getName())
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
     * Find a column in a table by either its Java field name or its SQL name. Java name is tried
     * first (case-insensitive), then SQL name (case-insensitive). Directive values in GraphQL
     * schemas may be either convention; trying Java name first handles custom jOOQ naming
     * strategies where {@code javaName} is not a simple {@code toUpperCase(sqlName)}.
     */
    public Optional<ColumnEntry> findColumn(Table<?> table, String columnName) {
        var entries = Arrays.stream(table.getClass().getFields())
            .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
            .map(f -> {
                var col = (org.jooq.Field<?>) instanceFieldValue(f, table);
                return new ColumnEntry(f.getName(), col.getType().getName(), col.getName(), col.getDataType().nullable());
            })
            .toList();
        return entries.stream().filter(e -> columnName.equalsIgnoreCase(e.javaName())).findFirst()
            .or(() -> entries.stream().filter(e -> columnName.equalsIgnoreCase(e.sqlName())).findFirst());
    }

    /**
     * Find a column by SQL table name and column name (Java or SQL). Delegates to
     * {@link #findTable(String)} followed by {@link #findColumn(Table, String)}.
     */
    public Optional<ColumnEntry> findColumn(String tableSqlName, String columnName) {
        return findTable(tableSqlName)
            .flatMap(e -> findColumn(e.table(), columnName));
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
            LOGGER.warn("{} did not contain a DefaultCatalog class — catalog lookups will return empty."
                + " This is expected in unit tests; in production it indicates a configuration error.",
                generatedJooqPackage);
            return null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(
                "Could not access " + generatedJooqPackage + ".DefaultCatalog.DEFAULT_CATALOG.", e);
        }
    }

    public record TableEntry(String javaFieldName, Table<?> table) {}

    /**
     * Node-identity metadata read from a table class by {@link #nodeIdMetadata(String)}.
     *
     * <p>{@code typeId} is the {@code __NODE_TYPE_ID} constant. {@code keyColumns} is the positional
     * resolution of {@code __NODE_KEY_COLUMNS} to {@link ColumnRef}s on the same table.
     */
    public record NodeIdMetadata(String typeId, List<ColumnRef> keyColumns) {}

    /**
     * Three-state outcome of a {@link #nodeIdMetadata} reflection probe. {@link Absent} means the
     * table class has no {@code __NODE_TYPE_ID} / {@code __NODE_KEY_COLUMNS} constants (legal for
     * non-NodeId tables). {@link Present} carries the validated metadata. {@link Malformed}
     * carries a human-readable reason suitable for a classifier-boundary diagnostic; the caller
     * prepends the {@code "KjerneJooqGenerator metadata on table 'X' is malformed: "} prefix.
     *
     * <p>Not part of the primary public API — callers use {@link #nodeIdMetadata(String)} and
     * {@link #nodeIdMetadataDiagnostic(String)}. The sum type itself is public so tests and
     * future classifier helpers can switch over the three cases directly.
     */
    public sealed interface NodeIdMetadataLookup {
        record Present(NodeIdMetadata metadata) implements NodeIdMetadataLookup {}
        record Absent() implements NodeIdMetadataLookup {}
        record Malformed(String reason) implements NodeIdMetadataLookup {}
    }

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
