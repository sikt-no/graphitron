package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;

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
 *   <li>{@code tableClass} — the generated jOOQ table class
 *       (e.g. {@code multischema_a.tables.Widget})</li>
 *   <li>{@code recordClass} — the generated jOOQ record class
 *       (e.g. {@code multischema_a.tables.records.WidgetRecord})</li>
 *   <li>{@code constantsClass} — the schema's {@code Tables} constants class
 *       (e.g. {@code multischema_a.Tables})</li>
 * </ul>
 *
 * <p>{@code primaryKeyColumns} is empty when the table has no primary key, or contains the
 * ordered list of PK columns (each a fully resolved {@link ColumnRef}) populated from
 * {@code table.getPrimaryKey().getFields()} at parse time.
 *
 * <p>{@code provenance} records whether the SQL table name came from a user-authored
 * {@code @table(name: "...")} argument or was inferred from the SDL type name. R160's
 * LSP inferred-directive arm reads this to render an inlay hint at sites where the user
 * omitted {@code name:}. Construction sites that lift a {@link TableRef} from the catalog
 * outside a {@code @table} directive context (e.g. {@link JooqCatalog.TableEntry#toTableRef}
 * called from internal lifters) default to {@link NameProvenance.Inferred.FromSdlName};
 * the LSP projector only reaches {@code TableRef}s through type-level carriers and never
 * sees the internal-lift defaults.
 *
 * <p>When the owning GraphQL type also carries {@code @node}, the type is classified as
 * {@link GraphitronType.NodeType} instead of {@link GraphitronType.TableType}, with the
 * {@code @node} directive properties ({@code typeId} and key columns) stored directly on it.
 */
public record TableRef(
    String tableName,
    String javaFieldName,
    ClassName tableClass,
    ClassName recordClass,
    ClassName constantsClass,
    List<ColumnRef> primaryKeyColumns,
    NameProvenance provenance
) {
    /**
     * Back-compat constructor for call sites that don't carry user-facing provenance
     * (PK column lifters, internal catalog conversions, tests). Defaults provenance to
     * {@link NameProvenance.Inferred.FromSdlName}; type-level classification at
     * {@code @table}-directive sites uses the full-arity constructor.
     */
    public TableRef(String tableName, String javaFieldName, ClassName tableClass,
                    ClassName recordClass, ClassName constantsClass,
                    List<ColumnRef> primaryKeyColumns) {
        this(tableName, javaFieldName, tableClass, recordClass, constantsClass,
             primaryKeyColumns, NameProvenance.inferredFromSdlName());
    }

    public boolean hasPrimaryKey() {
        return !primaryKeyColumns.isEmpty();
    }

    /** Returns a copy of this ref with the supplied provenance, leaving all other fields untouched. */
    public TableRef withProvenance(NameProvenance newProvenance) {
        return new TableRef(tableName, javaFieldName, tableClass, recordClass,
            constantsClass, primaryKeyColumns, newProvenance);
    }

    /**
     * Equality excludes {@link #provenance}: the field carries WHERE the SQL name came from
     * (authored argument vs SDL-name inference), not WHAT the table is. Two refs that point at
     * the same SQL identity must compare equal regardless of how the classifier sourced the
     * name; otherwise structural cross-checks like
     * "@mutation payload field returns the input's @table" misfire when one side carries
     * Authored and the other FromSdlName for the same physical table.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TableRef other)) return false;
        return java.util.Objects.equals(tableName, other.tableName)
            && java.util.Objects.equals(javaFieldName, other.javaFieldName)
            && java.util.Objects.equals(tableClass, other.tableClass)
            && java.util.Objects.equals(recordClass, other.recordClass)
            && java.util.Objects.equals(constantsClass, other.constantsClass)
            && java.util.Objects.equals(primaryKeyColumns, other.primaryKeyColumns);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(tableName, javaFieldName, tableClass, recordClass,
            constantsClass, primaryKeyColumns);
    }
}
