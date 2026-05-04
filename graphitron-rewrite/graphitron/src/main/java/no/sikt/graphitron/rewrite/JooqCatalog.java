package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ForeignKeyRef;
import org.jooq.Catalog;
import org.jooq.ForeignKey;
import org.jooq.Schema;
import org.jooq.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
    private final Map<String, Map<String, String>> qualifierMapCache = new ConcurrentHashMap<>();

    public JooqCatalog(String generatedJooqPackage) {
        this.catalog = loadDefaultCatalog(generatedJooqPackage);
    }

    /**
     * Find a table by its SQL name, with optional schema qualification. Accepts both
     * unqualified ({@code "film"}) and qualified ({@code "public.film"}) values; the input is
     * routed through {@link #parseQualifiedTableName(String)} and the appropriate two-arg
     * form below.
     *
     * <p>For unqualified values, the lookup scans all schemas and applies the strict ambiguity
     * policy: when the same table name appears in two or more schemas, the lookup returns
     * empty rather than first-schema-wins. Callers distinguish "not in catalog" from
     * "ambiguous" via {@link #findCandidateSchemasFor(String)}.
     *
     * <p>For qualified values, the lookup is scoped to the named schema and returns empty
     * when either the schema or the table-within-schema is missing. Schema and table matching
     * is case-insensitive on both halves (consistent with {@link #findColumn}).
     *
     * <p>Single-schema setups behave identically to the prior first-wins semantics. Multi-
     * schema setups with non-colliding table names also behave identically. Only collision
     * sites are forced to qualify.
     */
    public Optional<TableEntry> findTable(String sqlName) {
        return parseQualifiedTableName(sqlName).flatMap(qn -> qn.isQualified()
            ? findTable(qn.schema().get(), qn.table())
            : findUnqualifiedTable(qn.table()));
    }

    /**
     * Two-arg lookup form: scopes resolution to the named schema directly, never re-parses,
     * never scans across schemas. Used by callers that already hold a resolved schema (or
     * that have split a qualified name themselves) and need a stable, case-insensitive
     * table-within-schema lookup. The single-arg {@link #findTable(String)} delegates here
     * once parsing is done.
     *
     * <p>Returns empty when the schema is not in the catalog or the table-within-schema does
     * not exist; both halves are matched case-insensitively.
     */
    public Optional<TableEntry> findTable(String schemaSqlName, String tableSqlName) {
        if (catalog == null) return Optional.empty();
        return catalog.schemaStream()
            .filter(s -> s.getName().equalsIgnoreCase(schemaSqlName))
            .findFirst()
            .flatMap(s -> entriesIn(s)
                .filter(e -> e.table().getName().equalsIgnoreCase(tableSqlName))
                .findFirst());
    }

    /**
     * Returns the SQL schema names that contain a table with the given (unqualified) SQL
     * name, in catalog iteration order. Empty when the name is not in any schema; one element
     * when unique; two or more when ambiguous. Used by classifiers to distinguish "not in
     * catalog" from "ambiguous" when {@link #findTable(String)} returns empty for an
     * unqualified directive value.
     */
    public List<String> findCandidateSchemasFor(String unqualifiedSqlName) {
        if (catalog == null) return List.of();
        return catalog.schemaStream()
            .filter(s -> entriesIn(s).anyMatch(e -> e.table().getName().equalsIgnoreCase(unqualifiedSqlName)))
            .map(Schema::getName)
            .toList();
    }

    private Optional<TableEntry> findUnqualifiedTable(String unqualifiedSqlName) {
        if (catalog == null) return Optional.empty();
        var matches = catalog.schemaStream()
            .flatMap(s -> entriesIn(s)
                .filter(e -> e.table().getName().equalsIgnoreCase(unqualifiedSqlName)))
            .limit(2)
            .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private java.util.stream.Stream<TableEntry> entriesIn(Schema schema) {
        return tablesClass(schema).stream()
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> Table.class.isAssignableFrom(f.getType()))
            .map(f -> new TableEntry(f.getName(), (Table<?>) fieldValue(f), this));
    }

    /**
     * Parses a {@code @table(name:)} directive value into schema and table components.
     * Splits on the first {@code .}: {@code "public.film"} → schema {@code "public"}, table
     * {@code "film"}; {@code "film"} → schema empty, table {@code "film"}.
     *
     * <p>Returns empty for malformed input: {@code null}, blank, {@code "film."}, or
     * {@code ".film"}. Both halves must be non-empty when a {@code .} is present;
     * quoted-identifier syntax with literal dots ({@code "my.schema"."weird.table"}) is out
     * of scope for R78 and parses as malformed (the unmatched quote leaves a blank half).
     *
     * <p>Inputs with multiple dots ({@code "a.b.c"}) parse as schema {@code "a"}, table
     * {@code "b.c"}; the resulting two-arg lookup will return empty because PostgreSQL
     * identifiers can't contain unquoted dots, so the classifier routes such values to
     * {@link no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType}.
     */
    public static Optional<QualifiedTableName> parseQualifiedTableName(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        int dotIdx = value.indexOf('.');
        if (dotIdx < 0) {
            return Optional.of(new QualifiedTableName(Optional.empty(), value));
        }
        String schema = value.substring(0, dotIdx);
        String table = value.substring(dotIdx + 1);
        if (schema.isBlank() || table.isBlank()) return Optional.empty();
        return Optional.of(new QualifiedTableName(Optional.of(schema), table));
    }

    /**
     * A {@code @table(name:)} directive value parsed into its (optional schema, table) pair.
     * {@code schema} is empty for unqualified values; both halves are case-preserved from the
     * input so error messages can echo what the user wrote.
     */
    public record QualifiedTableName(Optional<String> schema, String table) {
        public boolean isQualified() {
            return schema.isPresent();
        }
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
            .map(f -> new TableEntry(f.getName(), (Table<?>) fieldValue(f), this))
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
        return findForeignKeyByName(sqlConstraintName).map(ForeignKeyRef::constantName);
    }

    /**
     * Resolves a foreign key by SQL constraint name into a typed {@link ForeignKeyRef} carrying
     * the schema-correct {@code Keys} {@link ClassName} together with the Java constant name.
     * Replaces per-emit-site {@code ClassName.get(jooqPackage, "Keys")} concatenation: the keys
     * class is read from the live {@link Class} of the matching FK constant, so multi-schema
     * codegen layouts produce schema-segmented FQNs (e.g. {@code multischema_a.Keys}) without
     * any caller-side derivation.
     *
     * <p>Empty when the catalog or schema {@code Keys} class is unavailable, or when no FK with
     * the given constraint name exists in any schema. The match is case-insensitive on the SQL
     * name (consistent with {@link #findForeignKey(String)}).
     */
    public Optional<ForeignKeyRef> findForeignKeyByName(String sqlConstraintName) {
        if (catalog == null) return Optional.empty();
        return catalog.schemaStream()
            .flatMap(schema -> keysClass(schema).stream())
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> ForeignKey.class.isAssignableFrom(f.getType()))
            .filter(f -> ((ForeignKey<?, ?>) fieldValue(f)).getName().equalsIgnoreCase(sqlConstraintName))
            .map(f -> new ForeignKeyRef(
                ((ForeignKey<?, ?>) fieldValue(f)).getName(),
                ClassName.get(f.getDeclaringClass()),
                f.getName()))
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

    /**
     * Returns the FK constraint name when exactly one outgoing FK from {@code sourceTableSqlName}
     * references {@code targetTableSqlName}; empty otherwise (zero or many).
     *
     * <p>Directional: only FKs where {@code sourceTableSqlName} is the FK source (not the target)
     * are counted. Uses {@link #findForeignKeysBetweenTables} and filters to the source side.
     */
    public Optional<String> findUniqueFkToTable(String sourceTableSqlName, String targetTableSqlName) {
        var matches = findForeignKeysBetweenTables(sourceTableSqlName, targetTableSqlName).stream()
            .filter(fk -> fk.getTable().getName().equalsIgnoreCase(sourceTableSqlName))
            .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0).getName()) : Optional.empty();
    }

    /**
     * Builds and caches a field-name → FK-constraint-name lookup map for all outgoing FKs from
     * {@code sourceTableSqlName}. For each FK three lowercase keys are inserted:
     * <ul>
     *   <li>the raw qualifier lowercased ({@code role + targetTable + "_id"}), matching
     *       {@code @field(name:)} values case-insensitively (e.g. {@code "language_id"});</li>
     *   <li>{@code lowerFirst(qualifier).toLowerCase()}, matching bare singular field names
     *       (e.g. {@code "languageid"});</li>
     *   <li>the plural of the above (e.g. {@code "languageids"}), matching bare list field names
     *       like {@code languageIds: [ID!]}.</li>
     * </ul>
     * The shim arm looks up {@code columnName.toLowerCase()} so SDL case differences never miss.
     * The map is cached; if two FKs on the same source table produce a colliding key (implying
     * duplicate methods in the generated record class) {@link IllegalStateException} is thrown.
     * Returns an empty map when the catalog is unavailable or the table has no outgoing FKs.
     */
    public Map<String, String> buildQualifierMap(String sourceTableSqlName) {
        return qualifierMapCache.computeIfAbsent(sourceTableSqlName, this::doBuildQualifierMap);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> doBuildQualifierMap(String sourceTableSqlName) {
        var tableEntry = findTable(sourceTableSqlName);
        if (tableEntry.isEmpty()) return Map.of();
        var result = new HashMap<String, String>();
        for (var fk : (List<ForeignKey<?, ?>>) (List<?>) tableEntry.get().table().getReferences()) {
            var qualifier = localGetQualifier(fk);
            var raw = (generateRolePrefix(fk) + fk.getKey().getTable().getName() + "_id").toLowerCase();
            var camel = lowerFirst(qualifier).toLowerCase();
            for (var key : List.of(raw, camel, camel + "s")) {
                var prev = result.put(key, fk.getName());
                if (prev != null && !prev.equals(fk.getName())) {
                    throw new IllegalStateException(
                        "FK qualifier map collision on key '" + key + "' for table '"
                        + sourceTableSqlName + "': FKs '" + prev + "' and '" + fk.getName()
                        + "' produce the same map key.");
                }
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Returns the UpperCamelCase qualifier that {@code KjerneJooqGenerator.getQualifier} would
     * produce for the FK named {@code fkName} on {@code sourceTableSqlName}
     * (e.g. {@code "LanguageId"} for {@code film_language_id_fkey} on {@code film}).
     * Empty when the FK is not found or does not originate from {@code sourceTableSqlName}.
     */
    public Optional<String> qualifierForFk(String sourceTableSqlName, String fkName) {
        return findForeignKey(fkName)
            .filter(fk -> fk.getTable().getName().equalsIgnoreCase(sourceTableSqlName))
            .map(JooqCatalog::localGetQualifier);
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

    // --- Qualifier reproduction (KjerneJooqGenerator.getQualifier) ---
    // Keep these helpers in sync with upstream KjerneJooqGenerator. They reproduce the
    // qualifier algorithm so the rewrite can map FK constraint names to has<Q>(s) predicate
    // method names and reverse-map field names back to FK constraints without a runtime
    // dependency on KjerneJooqGenerator itself.

    /**
     * Reproduces {@code KjerneJooqGenerator.generateRoleName}. Returns the role discriminator
     * derived from the last source/target column pair. {@code "HAR"} means the columns are equal
     * (no role prefix needed).
     */
    static String generateRoleName(List<String> sourceColumns, List<String> targetColumns) {
        var last = sourceColumns.size() - 1;
        var src = sourceColumns.get(last);
        var tgt = targetColumns.get(last);
        if (src.equals(tgt))         return "HAR";
        if (src.startsWith(tgt))     return src.substring(tgt.length() + 1);
        if (tgt.startsWith(src))     return tgt.substring(src.length() + 1);
        return src;
    }

    /**
     * Reproduces {@code KjerneJooqGenerator.generateRolePrefix}. Returns {@code ""} when the
     * role is {@code "HAR"} (no distinguishing prefix), otherwise {@code role + "_"}.
     */
    static String generateRolePrefix(ForeignKey<?, ?> fk) {
        var srcCols = fk.getFields().stream().map(f -> f.getName().toLowerCase()).toList();
        var tgtCols = fk.getKey().getFields().stream().map(f -> f.getName().toLowerCase()).toList();
        var role = generateRoleName(srcCols, tgtCols);
        return "HAR".equals(role) ? "" : role + "_";
    }

    /**
     * Reproduces {@code KjerneJooqGenerator.getQualifier}. Returns an UpperCamelCase qualifier
     * derived from the FK's role prefix and target table name, e.g. {@code "LanguageId"} or
     * {@code "OriginalLanguageIdLanguageId"}.
     */
    static String localGetQualifier(ForeignKey<?, ?> fk) {
        var role = generateRolePrefix(fk);
        var raw = (role + fk.getKey().getTable().getName() + "_id").toLowerCase();
        return Arrays.stream(raw.split("_"))
            .map(s -> s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1))
            .collect(Collectors.joining());
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * A table resolved from the jOOQ catalog: the {@link Table} instance, its Java field name in
     * the schema's {@code Tables} constants class (e.g. {@code "FILM"}), and a back-reference to
     * the catalog used for derived lookups (PK columns, the schema's {@code Tables} class).
     *
     * <p>The accessor methods ({@link #tableClass()}, {@link #recordClass()},
     * {@link #constantsClass()}, {@link #pkColumnRefs()}) replace the per-emit-site reflection
     * and {@code <jooqPackage>}-concatenation that R78 retires. Each accessor reads schema-
     * correct values from the resolved {@link Table}, so single-schema and multi-schema
     * catalog layouts produce the same call shape with no per-caller derivation.
     *
     * <p>The catalog reference is intentionally part of the record's identity — within a build
     * all entries come from one {@code JooqCatalog} instance, so its presence in {@code equals}
     * is a no-op for normal use; tests that compare entries across separate catalog instances
     * should compare {@link #table()} or {@link #javaFieldName()} explicitly.
     */
    public record TableEntry(String javaFieldName, Table<?> table, JooqCatalog catalog) {
        /**
         * The {@link ClassName} of the jOOQ-generated table class
         * (e.g. {@code multischema_a.tables.Widget}). Read directly from the live class via
         * reflection, so multi-schema layouts produce schema-segmented FQNs without per-caller
         * derivation.
         */
        public ClassName tableClass() {
            return ClassName.get(table.getClass());
        }

        /**
         * The {@link ClassName} of the jOOQ-generated record class
         * (e.g. {@code multischema_a.tables.records.WidgetRecord}). Sourced from
         * {@link Table#getRecordType()}, which is schema-correct for both single- and
         * multi-schema codegen.
         */
        public ClassName recordClass() {
            return ClassName.get(table.getRecordType());
        }

        /**
         * The {@link ClassName} of the schema's {@code Tables} constants class
         * (e.g. {@code multischema_a.Tables}). Empty when the schema package contains no
         * generated {@code Tables} class — a degenerate consumer-side codegen state that
         * R78 surfaces as {@link no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType}
         * instead of hiding behind an empty-string fallback.
         */
        public Optional<ClassName> constantsClass() {
            return catalog.tablesClass(table.getSchema()).map(ClassName::get);
        }

        /**
         * The primary-key columns of this table in key-field order, materialised as
         * {@link ColumnRef}s with the typed Java field names, SQL names, and column-class
         * FQNs. Empty list when the table has no primary key. Same shape as
         * {@link JooqCatalog#findPkColumns(String)} but scoped to this resolved entry without
         * a second catalog lookup.
         */
        public List<ColumnRef> pkColumnRefs() {
            var pk = table.getPrimaryKey();
            if (pk == null) return List.of();
            return pk.getFields().stream()
                .map(f -> catalog.findColumn(table, f.getName()))
                .<ColumnEntry>flatMap(Optional::stream)
                .map(ce -> new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()))
                .toList();
        }

        /**
         * Materialises a {@link no.sikt.graphitron.rewrite.model.TableRef} from this entry. The
         * {@code sqlName} argument is the directive-supplied form (case-preserved for error
         * messages), not {@code entry.table().getName()} — the catalog lookup key is preserved
         * from the user-visible name even when jOOQ canonicalises differently.
         *
         * <p>Empty when {@link #constantsClass()} is empty (a degenerate consumer-side
         * codegen state where the schema package has no generated {@code Tables} class). The
         * caller routes empty to {@link no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType}.
         */
        public Optional<no.sikt.graphitron.rewrite.model.TableRef> toTableRef(String sqlName) {
            return constantsClass().map(cc -> new no.sikt.graphitron.rewrite.model.TableRef(
                sqlName,
                javaFieldName,
                tableClass(),
                recordClass(),
                cc,
                pkColumnRefs()));
        }
    }

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
