package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.emit.EntityKeyQuery;
import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.List;

/**
 * What the entity dispatch renders off one of {@link EntityKeyQuery}'s key alternatives: the fields a
 * representation must carry to match it, and the columns it selects the entity's row by.
 */
final class EntityKeys {

    private EntityKeys() {}

    /**
     * The fields a representation must carry to match {@code alternative}: a node id carries {@code id},
     * a voucher alternative the minted key's one field, the voucher.
     */
    static List<String> requiredFields(EntityKeyQuery.Alternative alternative) {
        return switch (alternative) {
            case EntityKeyQuery.Alternative.Direct direct ->
                direct.fields().stream().map(EntityKeyQuery.KeyField::fieldName).toList();
            case EntityKeyQuery.Alternative.NodeId ignored -> List.of("id");
            case EntityKeyQuery.Alternative.Voucher voucher -> List.of(voucher.voucherField());
        };
    }

    /**
     * The columns {@code alternative} selects the entity's row by, in key order. A voucher alternative
     * selects by none of its own: its lookup part decodes onto the authored key it names, whose batch and
     * SELECT it loads through, so asking it for columns is a generator fault.
     */
    static List<ColumnRef> columns(EntityKeyQuery.Alternative alternative) {
        return switch (alternative) {
            case EntityKeyQuery.Alternative.Direct direct ->
                direct.fields().stream().map(f -> columnRef(f.column())).toList();
            case EntityKeyQuery.Alternative.NodeId nodeId ->
                nodeId.keyColumns().stream().map(EntityKeys::columnRef).toList();
            case EntityKeyQuery.Alternative.Voucher voucher -> throw voucherHasNoSelect(voucher);
        };
    }

    /**
     * The generator fault of asking a voucher alternative for a SELECT of its own: it loads through the
     * authored key it decodes onto, and the tenanted dispatch, which groups by columns, never meets one,
     * since {@code @accept} in a {@code <tenantColumn>} build is refused.
     */
    static IllegalStateException voucherHasNoSelect(EntityKeyQuery.Alternative.Voucher voucher) {
        return new IllegalStateException("The voucher alternative #" + voucher.ordinal() + " selects by no column"
            + " of its own: it loads through the authored key it decodes onto, and the tenanted dispatch never"
            + " reads one, @accept with a <tenantColumn> being refused");
    }

    /**
     * The entity's {@code Tables} class, through which a column of its table is reached. A schema that
     * publishes none leaves the dispatch no name for the columns, so this throws rather than the entity
     * being left out, which would answer it as one this subgraph cannot resolve.
     */
    static String tablesClass(EntityKeyQuery.Entity entity) {
        if (entity.tablesClass() == null) {
            throw new IllegalStateException("Entity '" + entity.typeName() + "' is a row of "
                + entity.tableSchema() + "." + entity.tableName() + ", whose schema publishes no Tables class"
                + " for the dispatch to name its columns through");
        }
        return entity.tablesClass();
    }

    static ColumnRef columnRef(EntityKeyQuery.Column column) {
        return new ColumnRef(column.columnName(), column.javaName(), column.bindingType(), column.databaseType());
    }
}
