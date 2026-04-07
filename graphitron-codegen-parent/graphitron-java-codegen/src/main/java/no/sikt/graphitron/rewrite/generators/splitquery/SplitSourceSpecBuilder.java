package no.sikt.graphitron.rewrite.generators.splitquery;

import no.sikt.graphitron.rewrite.JooqCatalog.ColumnEntry;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.field.ChildField.TableField;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkWithConditionRef;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;

import java.util.List;

/**
 * Builds {@link SplitSourceSpec} instances from a {@link GraphitronSchema}.
 *
 * <p>Scans all {@link no.sikt.graphitron.rewrite.field.ChildField.TableField} instances where
 * {@link no.sikt.graphitron.rewrite.field.ChildField.TableField#splitQuery()} is {@code true} and
 * produces one {@link SplitSourceSpec} per qualifying field.
 *
 * <p>The FK is resolved from the first element of
 * {@link no.sikt.graphitron.rewrite.field.ChildField.TableField#referencePath()} that carries a
 * jOOQ {@link org.jooq.ForeignKey}. The parent-side key columns — those belonging to the parent
 * type's table — are taken from the pre-resolved {@link ColumnEntry} lists on {@link FkRef} or
 * {@link FkWithConditionRef}. No reflection is performed here; all Java identifier names were
 * resolved by {@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilder} at schema-build time.
 *
 * <p>Fields with no resolvable FK, an unresolved parent table, or an empty key-field list are
 * silently dropped — the validator already reports those errors.
 */
public class SplitSourceSpecBuilder {

    public static List<SplitSourceSpec> build(GraphitronSchema schema) {
        return schema.fields().values().stream()
            .filter(f -> f instanceof TableField tf && tf.splitQuery())
            .map(f -> (TableField) f)
            .map(field -> buildSpec(field, schema))
            .filter(spec -> spec != null && !spec.keyFields().isEmpty())
            .toList();
    }

    private static SplitSourceSpec buildSpec(TableField field, GraphitronSchema schema) {
        var parentType = schema.types().get(field.parentTypeName());
        if (!(parentType instanceof TableType tt)) return null;
        if (!(tt.table() instanceof ResolvedTable rt)) return null;

        var fkRef = firstFkRef(field.referencePath());
        if (fkRef == null) return null;

        var keyFields = buildKeyFields(fkRef, rt.tableName());
        if (keyFields.isEmpty()) return null;

        return new SplitSourceSpec(
            field.parentTypeName(),
            field.name(),
            rt.javaFieldName(),
            keyFields
        );
    }

    private static FkRef firstFkRef(List<ReferencePathElementRef> path) {
        for (var el : path) {
            if (el instanceof FkRef r) return r;
            if (el instanceof FkWithConditionRef r)
                return new FkRef(r.key(), r.keyColumnEntries(), r.fkColumnEntries());
        }
        return null;
    }

    /**
     * Returns the parent-side columns of the FK: the columns that belong to the parent table and
     * should be extracted from each source record.
     *
     * <p>When the FK is declared on the child table and references the parent (the common case —
     * e.g. {@code film.language_id → language.language_id}), the parent-side columns are the
     * referenced key fields: {@link FkRef#keyColumnEntries()}.
     *
     * <p>When the FK is declared on the parent table and references the child, the parent-side
     * columns are the referencing FK fields: {@link FkRef#fkColumnEntries()}.
     */
    private static List<SplitSourceKeyFieldSpec> buildKeyFields(FkRef fkRef, String parentTableSqlName) {
        List<ColumnEntry> entries = fkRef.keyTableSqlName().equalsIgnoreCase(parentTableSqlName)
            ? fkRef.keyColumnEntries()
            : fkRef.fkColumnEntries();

        return entries.stream()
            .map(e -> new SplitSourceKeyFieldSpec(e.javaName(), e.columnClass()))
            .toList();
    }
}
