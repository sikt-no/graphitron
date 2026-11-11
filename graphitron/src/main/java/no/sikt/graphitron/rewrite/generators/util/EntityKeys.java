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

    /** The fields a representation must carry to match {@code alternative}: a node id carries {@code id}. */
    static List<String> requiredFields(EntityKeyQuery.Alternative alternative) {
        return switch (alternative) {
            case EntityKeyQuery.Alternative.Direct direct ->
                direct.fields().stream().map(EntityKeyQuery.KeyField::fieldName).toList();
            case EntityKeyQuery.Alternative.NodeId ignored -> List.of("id");
        };
    }

    /** The columns {@code alternative} selects the entity's row by, in key order. */
    static List<ColumnRef> columns(EntityKeyQuery.Alternative alternative) {
        return switch (alternative) {
            case EntityKeyQuery.Alternative.Direct direct ->
                direct.fields().stream().map(f -> columnRef(f.column())).toList();
            case EntityKeyQuery.Alternative.NodeId nodeId ->
                nodeId.keyColumns().stream().map(EntityKeys::columnRef).toList();
        };
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

    private static ColumnRef columnRef(EntityKeyQuery.Column column) {
        return new ColumnRef(column.columnName(), column.javaName(), column.bindingType(), column.databaseType());
    }
}
