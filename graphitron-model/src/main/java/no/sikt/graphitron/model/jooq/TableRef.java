package no.sikt.graphitron.model.jooq;

import java.util.List;
import java.util.Optional;

/**
 * A jOOQ table resolved from a {@code @table} directive value.
 *
 * <p>A {@code TableRef} is only constructed when the table name matches an entry in the jOOQ
 * catalog. When the name cannot be matched the containing type is classified as
 * {@code GraphitronType.UnclassifiedType} at build time; emitters never see a partial ref.
 *
 * <p>{@code tableName} is the raw SQL table name from the {@code @table} directive
 * (e.g. {@code "film"}), case-preserved from the directive value so error messages echo what
 * the user wrote.
 *
 * <p>{@code javaFieldName} is the field name in the schema's {@code Tables} constants class
 * (e.g. {@code "FILM"} for {@code Tables.FILM}).
 *
 * <p>{@code tableClassName} / {@code recordClassName} / {@code constantsClassName} are fully
 * qualified names read directly from jOOQ reflection at parse time, so multi-schema catalog
 * layouts produce schema-segmented FQNs without per-emit-site derivation. Names and not javapoet
 * types: a consumer emitting one lifts it through {@code CatalogRefs}, which is where
 * deciding how a name is written into a source file belongs.
 *
 * <ul>
 *   <li>{@code tableClassName}: the generated jOOQ table class
 *       (e.g. {@code multischema_a.tables.Widget})</li>
 *   <li>{@code recordClassName}: the generated jOOQ record class
 *       (e.g. {@code multischema_a.tables.records.WidgetRecord})</li>
 *   <li>{@code constantsClassName}: the schema's {@code Tables} constants class
 *       (e.g. {@code multischema_a.Tables})</li>
 * </ul>
 *
 * <p>{@code primaryKeyColumns} is empty when the table has no primary key, or contains the
 * ordered list of PK columns (each a fully resolved {@link ColumnRef}) populated from
 * {@code table.getPrimaryKey().getFields()} at parse time.
 *
 * <p>{@code allColumns} is the ordered list of every column on the table (each a fully resolved
 * {@link ColumnRef}), populated in the same catalog traversal that fixes
 * {@code primaryKeyColumns}. It answers "what columns does this table have?" for classification,
 * which runs after the catalog is closed and so cannot reach back for the answer. Every reader is
 * classification-time; no generator drives a per-column emit off it, because a key extraction
 * takes the columns it needs from the field's own {@code SourceKey}. Empty only when constructed
 * outside the catalog flow (test fixtures that do not exercise those paths).
 *
 * <p>A type carrying both {@code @table} and {@code @node} is classified as
 * {@code GraphitronType.NodeType} rather than {@code GraphitronType.TableType}.
 */
public record TableRef(
    String tableName,
    String javaFieldName,
    String tableClassName,
    String recordClassName,
    String constantsClassName,
    List<ColumnRef> primaryKeyColumns,
    List<ColumnRef> allColumns
) {
    public boolean hasPrimaryKey() {
        return !primaryKeyColumns.isEmpty();
    }

    /**
     * Resolves a column on this table by either its Java field name or its SQL name, matching
     * {@code JooqCatalog.findColumn}'s order: Java name first (case-insensitive, across all
     * columns), then SQL name (case-insensitive). Directive values in GraphQL schemas may be
     * either convention; trying Java name first handles custom jOOQ naming strategies where
     * {@code javaName} is not a simple {@code toUpperCase(sqlName)}.
     *
     * <p>This is the model-side matcher home: a consumer that already holds an
     * identity-resolved ref resolves columns here instead of collapsing to a bare SQL name and
     * re-resolving through the catalog, which is ambiguous when the table name collides across
     * schemas. Returns empty on an unknown column, and always on refs constructed outside the
     * catalog flow (hand-built test fixtures leave {@code allColumns} empty).
     */
    public Optional<ColumnRef> column(String columnName) {
        return allColumns.stream().filter(c -> columnName.equalsIgnoreCase(c.javaName())).findFirst()
            .or(() -> allColumns.stream().filter(c -> columnName.equalsIgnoreCase(c.sqlName())).findFirst());
    }

    /**
     * True when {@code other} names this table, compared case-insensitively. {@code tableName()}
     * stays the verbatim {@code @table(name:)} echo for diagnostics; this is the canonical
     * name comparison, so consumers never re-establish the case-folding contract the jOOQ
     * catalog already guarantees. Null-safe: a null {@code other} is not this table.
     */
    public boolean sameTable(String other) {
        return other != null && tableName.equalsIgnoreCase(other);
    }

    /**
     * True when {@code other} denotes the same table as this ref. Compares the reified jOOQ
     * table-class identity ({@code tableClassName}) when both sides carry one; that is what
     * distinguishes same-named tables across schemas and matches a schema-qualified {@code @table}
     * echo against jOOQ's unqualified canonical name. Falls back to the case-insensitive name
     * compare ({@link #sameTable(String)}) only when either side lacks a {@code tableClassName}, which
     * catalog-constructed refs never do ({@code JooqCatalog.TableEntry.toTableRef} always populates
     * it); the fallback exists for fixture-built partial refs in unit tests. Null-safe: a null
     * {@code other} is not this table.
     *
     * <p>This is the model-side identity home for the same-table question. It agrees by
     * construction with {@code JooqCatalog}'s parse-boundary primitives, which compare raw
     * jOOQ {@code Table<?>} classes while the raw objects are still in scope: both derive from
     * the same generated jOOQ class at parse time. A consumer at the parse boundary with raw
     * jOOQ objects uses the catalog primitive; past the boundary with model refs, this
     * predicate. Do not grow a third mechanism.
     */
    public boolean denotesSameTableAs(TableRef other) {
        if (other == null) {
            return false;
        }
        if (tableClassName != null && other.tableClassName() != null) {
            return tableClassName.equals(other.tableClassName());
        }
        return sameTable(other.tableName());
    }
}
