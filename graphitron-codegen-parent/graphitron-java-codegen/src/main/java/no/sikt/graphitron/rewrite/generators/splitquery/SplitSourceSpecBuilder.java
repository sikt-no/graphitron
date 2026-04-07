package no.sikt.graphitron.rewrite.generators.splitquery;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.field.ChildField.TableField;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkWithConditionRef;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;
import org.jooq.ForeignKey;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Builds {@link SplitSourceSpec} instances from a {@link GraphitronSchema}.
 *
 * <p>Scans all {@link no.sikt.graphitron.rewrite.field.ChildField.TableField} instances where
 * {@link no.sikt.graphitron.rewrite.field.ChildField.TableField#splitQuery()} is {@code true} and
 * produces one {@link SplitSourceSpec} per qualifying field.
 *
 * <p>The FK is resolved from the first element of
 * {@link no.sikt.graphitron.rewrite.field.ChildField.TableField#referencePath()} that carries a
 * jOOQ {@link ForeignKey}. The parent-side key columns (the columns belonging to the parent type's
 * table) are used as the columns in the derived source table.
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

        var fk = firstFk(field.referencePath());
        if (fk == null) return null;

        var keyFields = buildKeyFields(fk, rt.table());
        if (keyFields.isEmpty()) return null;

        return new SplitSourceSpec(
            field.parentTypeName(),
            field.name(),
            rt.javaFieldName(),
            keyFields
        );
    }

    private static ForeignKey<?, ?> firstFk(List<ReferencePathElementRef> path) {
        for (var el : path) {
            if (el instanceof FkRef r) return r.key();
            if (el instanceof FkWithConditionRef r) return r.key();
        }
        return null;
    }

    /**
     * Returns the parent-side columns of the FK: the columns that belong to the parent table and
     * should be extracted from each source record.
     *
     * <p>When the FK is declared on the child table and references the parent (the common case —
     * e.g. {@code film.language_id → language.language_id}), the parent-side columns are the
     * referenced key fields: {@code fk.getKey().getFields()}.
     *
     * <p>When the FK is declared on the parent table and references the child, the parent-side
     * columns are the referencing FK fields: {@code fk.getFields()}.
     */
    @SuppressWarnings("unchecked")
    private static List<SplitSourceKeyFieldSpec> buildKeyFields(ForeignKey<?, ?> fk, org.jooq.Table<?> parentTable) {
        List<? extends org.jooq.TableField<?, ?>> parentKeyFields;
        if (fk.getKey().getTable().getName().equalsIgnoreCase(parentTable.getName())) {
            parentKeyFields = (List<? extends org.jooq.TableField<?, ?>>) fk.getKey().getFields();
        } else {
            parentKeyFields = (List<? extends org.jooq.TableField<?, ?>>) fk.getFields();
        }

        return parentKeyFields.stream()
            .map(kf -> {
                String javaName = javaFieldName(parentTable, kf.getName())
                    .orElse(kf.getName().toUpperCase());
                String columnClass = kf.getType().getName();
                return new SplitSourceKeyFieldSpec(javaName, columnClass);
            })
            .toList();
    }

    private static Optional<String> javaFieldName(org.jooq.Table<?> table, String sqlName) {
        return Arrays.stream(table.getClass().getFields())
            .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
            .filter(f -> {
                try {
                    var field = (org.jooq.Field<?>) f.get(table);
                    return sqlName.equalsIgnoreCase(field.getName());
                } catch (IllegalAccessException e) {
                    return false;
                }
            })
            .map(java.lang.reflect.Field::getName)
            .findFirst();
    }
}
