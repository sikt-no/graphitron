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
    List<ColumnRef> primaryKeyColumns
) {
    public boolean hasPrimaryKey() {
        return !primaryKeyColumns.isEmpty();
    }
}
