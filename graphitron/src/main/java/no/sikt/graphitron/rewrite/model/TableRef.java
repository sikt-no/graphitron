package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;
import java.util.Optional;

/**
 * A jOOQ table resolved from a {@code @table} directive value.
 *
 * <p>A {@code TableRef} is only constructed when the table name matches an entry in the jOOQ
 * catalog. When the name cannot be matched the containing type is classified as
 * {@link GraphitronType.UnclassifiedType} at build time; emitters never see a partial ref.
 *
 * <p>{@code tableName} is the raw SQL table name from the {@code @table} directive
 * (e.g. {@code "film"}), case-preserved from the directive value so error messages echo what
 * the user wrote.
 *
 * <p>{@code javaFieldName} is the field name in the schema's {@code Tables} constants class
 * (e.g. {@code "FILM"} for {@code Tables.FILM}).
 *
 * <p>{@code tableClass} / {@code recordClass} / {@code constantsClass} are typed
 * {@link ClassName}s read directly from jOOQ reflection at parse time, so multi-schema
 * catalog layouts produce schema-segmented FQNs without per-emit-site derivation:
 *
 * <ul>
 *   <li>{@code tableClass}: the generated jOOQ table class
 *       (e.g. {@code multischema_a.tables.Widget})</li>
 *   <li>{@code recordClass}: the generated jOOQ record class
 *       (e.g. {@code multischema_a.tables.records.WidgetRecord})</li>
 *   <li>{@code constantsClass}: the schema's {@code Tables} constants class
 *       (e.g. {@code multischema_a.Tables})</li>
 * </ul>
 *
 * <p>{@code primaryKeyColumns} is empty when the table has no primary key, or contains the
 * ordered list of PK columns (each a fully resolved {@link ColumnRef}) populated from
 * {@code table.getPrimaryKey().getFields()} at parse time.
 *
 * <p>{@code allColumns} is the ordered list of every column on the table (each a fully resolved
 * {@link ColumnRef}), populated in the same catalog traversal that fixes
 * {@code primaryKeyColumns}. It exists so consumers can enumerate the whole row without reaching
 * back for the catalog (which is closed by then). The consumers are classification-time and all
 * answer "what columns does this table have?": {@code TypeBuilder}'s interface base/detail split,
 * {@code BuildContext}'s source-column resolution, and {@code FieldBuilder}'s pivot-column search
 * (all package-private in the enclosing package, hence named rather than linked). No generator
 * drives a per-column emit off it; a key extraction takes the columns it needs from the field's own
 * {@link SourceKey}. Empty only when constructed outside the catalog flow (test fixtures that do
 * not exercise those paths).
 *
 * <p>A type carrying both {@code @table} and {@code @node} is classified as
 * {@link GraphitronType.NodeType} rather than {@link GraphitronType.TableType}.
 */
public record TableRef(
    String tableName,
    String javaFieldName,
    ClassName tableClass,
    ClassName recordClass,
    ClassName constantsClass,
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
     * table-class identity ({@code tableClass}) when both sides carry one; that is what
     * distinguishes same-named tables across schemas and matches a schema-qualified {@code @table}
     * echo against jOOQ's unqualified canonical name. Falls back to the case-insensitive name
     * compare ({@link #sameTable(String)}) only when either side lacks a {@code tableClass}, which
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
        if (tableClass != null && other.tableClass() != null) {
            return tableClass.equals(other.tableClass());
        }
        return sameTable(other.tableName());
    }
}
