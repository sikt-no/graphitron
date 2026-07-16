package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.JooqCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The frozen, SQL-name-centric catalog-data projection the {@code catalog.tables} /
 * {@code catalog.describe} MCP tools read. Built once per catalog rebuild in
 * {@link CatalogBuilder#buildCatalogFacts(JooqCatalog)} while the codegen loader is still open
 * (R361 D1, build-time enrichment), carried alongside {@link CompletionData} on the build
 * artifacts, and swapped onto the live {@code Workspace} on every generator pass.
 *
 * <p><b>Load-bearing invariant:</b> every field on this record and its nested records holds only
 * <em>resolved immutable values</em> — {@link String}, {@code boolean}, {@link List}, {@link Map},
 * {@link Optional}. It must never retain a live jOOQ reflection handle ({@code Table<?>},
 * {@code ForeignKey<?,?>}, {@code org.jooq.Field}) or a {@code Class<?>}, because the
 * {@code codegenLoader} those reflect against is closed at the end of each {@code withCodegenScope}
 * pass. Reading {@code CatalogFacts} after the loader closes must return the same values without a
 * {@code NoClassDefFoundError}; the {@link CatalogBuilder} pass reduces every live handle to a
 * {@code String} during construction so nothing lazy survives the build boundary.
 *
 * <p>This is a sibling projection to the LSP's {@link CompletionData}, not a widening of it: the
 * two views share the one build-time catalog traversal but each carries exactly what its consumer
 * reads. {@code CompletionData.Column} keeps only the jOOQ Java field name (LSP completions suggest
 * the Java form); {@code CatalogFacts} is SQL-name-centric (discovery keys off the SQL name) and
 * carries the richer facts {@code catalog.describe} promises: SQL-and-Java column names, SQL data
 * types, primary / unique keys, indexes, and foreign keys in both directions with their column
 * pairs.
 *
 * <p>The map is keyed by the schema-qualified SQL table name ({@code "schema.table"}), which is the
 * stable table ID R118 walks: FK endpoints ({@link OutgoingForeignKey#targetTable()} /
 * {@link IncomingForeignKey#sourceTable()}) name neighbours by that same ID. Iteration order is the
 * stable schema-then-table order the build fixes; {@code catalog.tables} pages over it.
 */
public record CatalogFacts(Map<String, Table> tablesByQualifiedName) {

    public CatalogFacts {
        tablesByQualifiedName = orderedCopy(tablesByQualifiedName);
    }

    private static Map<String, Table> orderedCopy(Map<String, Table> in) {
        return in == null ? Map.of()
            : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(in));
    }

    /** The empty projection carried until the first successful build. */
    public static CatalogFacts empty() {
        return new CatalogFacts(Map.of());
    }

    /**
     * Tables in the stable build-fixed order, optionally narrowed by {@code schemaFilter} (exact,
     * case-insensitive) and {@code nameSubstring} (case-insensitive substring on the SQL table
     * name). Both filters absent returns the whole catalog in order. Paging (limit / cursor) is a
     * wire concern the {@code catalog.tables} tool layers on top of this ordered list.
     */
    public List<Table> tables(Optional<String> schemaFilter, Optional<String> nameSubstring) {
        var name = nameSubstring.map(s -> s.toLowerCase());
        return tablesByQualifiedName.values().stream()
            .filter(t -> schemaFilter.map(s -> t.schema().equalsIgnoreCase(s)).orElse(true))
            .filter(t -> name.map(n -> t.name().toLowerCase().contains(n)).orElse(true))
            .toList();
    }

    /**
     * Resolves one table for {@code catalog.describe}, mirroring {@link JooqCatalog.TableResolution}
     * semantics over this frozen projection. {@code tableArg} may be bare ({@code "film"}) or
     * schema-qualified ({@code "public.film"}); {@code schemaArg} is the alternative to inline
     * qualification (inline wins when both are present). A unique match returns
     * {@link TableResolution.Resolved}; an unqualified name two or more schemas carry returns
     * {@link TableResolution.Ambiguous} naming the candidate schemas; anything else returns
     * {@link TableResolution.NotFound}. All matching is case-insensitive on both halves.
     */
    public TableResolution resolve(String tableArg, Optional<String> schemaArg) {
        var parsed = JooqCatalog.parseQualifiedTableName(tableArg);
        if (parsed.isEmpty()) {
            return new TableResolution.NotFound();
        }
        var qn = parsed.get();
        Optional<String> schema = qn.isQualified() ? qn.schema() : schemaArg.filter(s -> !s.isBlank());
        String name = qn.table();
        if (schema.isPresent()) {
            return tablesByQualifiedName.values().stream()
                .filter(t -> t.schema().equalsIgnoreCase(schema.get()) && t.name().equalsIgnoreCase(name))
                .findFirst()
                .<TableResolution>map(TableResolution.Resolved::new)
                .orElseGet(TableResolution.NotFound::new);
        }
        var matches = tablesByQualifiedName.values().stream()
            .filter(t -> t.name().equalsIgnoreCase(name))
            .toList();
        return switch (matches.size()) {
            case 0 -> new TableResolution.NotFound();
            case 1 -> new TableResolution.Resolved(matches.get(0));
            default -> new TableResolution.Ambiguous(matches.stream().map(Table::schema).toList());
        };
    }

    /**
     * One table's frozen facts. {@code schema} and {@code name} are the raw SQL names; {@code comment}
     * is the jOOQ table comment when the runtime catalog carried one (empty otherwise, never
     * empty-string-valued). {@code primaryKey} is present when the table has one; {@code uniqueKeys}
     * excludes the PK and is deduplicated on column set (as {@code JooqCatalog.candidateKeys}
     * already does).
     */
    public record Table(
        String schema,
        String name,
        Optional<String> comment,
        List<Column> columns,
        Optional<Key> primaryKey,
        List<Key> uniqueKeys,
        List<Index> indexes,
        ForeignKeys foreignKeys
    ) {
        public Table {
            columns = List.copyOf(columns);
            uniqueKeys = List.copyOf(uniqueKeys);
            indexes = List.copyOf(indexes);
        }

        /** The schema-qualified SQL name ({@code "schema.name"}); the stable table ID. */
        public String qualifiedName() {
            return schema + "." + name;
        }
    }

    /**
     * One column. {@code sqlName} is the SQL column name (the discovery key); {@code javaName} is the
     * jOOQ-generated Java field constant; {@code sqlType} is the jOOQ SQL data-type name;
     * {@code comment} is the column comment, present only when jOOQ codegen captured comments
     * (degrades to absent otherwise, never empty-string-valued).
     */
    public record Column(
        String sqlName,
        String javaName,
        String sqlType,
        boolean nullable,
        Optional<String> comment
    ) {}

    /** A primary or unique key: its SQL constraint name and its columns by SQL name in key order. */
    public record Key(String constraintName, List<String> columns) {
        public Key {
            columns = List.copyOf(columns);
        }
    }

    /** An index: its name and its columns by SQL name in index order. */
    public record Index(String name, List<String> columns) {
        public Index {
            columns = List.copyOf(columns);
        }
    }

    /** A table's foreign keys split by direction. */
    public record ForeignKeys(List<OutgoingForeignKey> outgoing, List<IncomingForeignKey> incoming) {
        public ForeignKeys {
            outgoing = List.copyOf(outgoing);
            incoming = List.copyOf(incoming);
        }

        public static ForeignKeys empty() {
            return new ForeignKeys(List.of(), List.of());
        }
    }

    /**
     * An outgoing foreign key: this table's {@code columns} reference {@code targetColumns} on
     * {@code targetTable} (a schema-qualified table ID). {@code constraintName} is the SQL FK name.
     */
    public record OutgoingForeignKey(
        String constraintName,
        String targetTable,
        List<String> columns,
        List<String> targetColumns
    ) {
        public OutgoingForeignKey {
            columns = List.copyOf(columns);
            targetColumns = List.copyOf(targetColumns);
        }
    }

    /**
     * An incoming foreign key: {@code sourceTable} (a schema-qualified table ID) holds an FK whose
     * {@code columns} reference {@code targetColumns} on this table. {@code constraintName} is the
     * SQL FK name.
     */
    public record IncomingForeignKey(
        String constraintName,
        String sourceTable,
        List<String> columns,
        List<String> targetColumns
    ) {
        public IncomingForeignKey {
            columns = List.copyOf(columns);
            targetColumns = List.copyOf(targetColumns);
        }
    }

    /**
     * Outcome of {@link #resolve(String, Optional)}: the {@code catalog.describe} resolution
     * sub-taxonomy, parallel to {@link JooqCatalog.TableResolution} but over the frozen
     * {@link Table} (never the live {@code JooqCatalog.TableEntry}, which holds a {@code Table<?>}).
     */
    public sealed interface TableResolution {
        /** Exactly one table matched. */
        record Resolved(Table table) implements TableResolution {}

        /** An unqualified name two or more schemas carry; {@code schemas} names the candidates. */
        record Ambiguous(List<String> schemas) implements TableResolution {
            public Ambiguous {
                schemas = List.copyOf(schemas);
            }
        }

        /** No table matched. */
        record NotFound() implements TableResolution {}
    }
}
