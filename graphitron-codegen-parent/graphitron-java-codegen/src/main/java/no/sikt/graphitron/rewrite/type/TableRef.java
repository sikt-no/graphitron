package no.sikt.graphitron.rewrite.type;

import java.util.List;

/**
 * Represents the outcome of resolving a {@code @table} directive value to a jOOQ table class.
 *
 * <p>Both implementations carry the raw SQL table name ({@link #tableName()}) that was used in
 * the resolution attempt, so callers always have access to it regardless of whether resolution
 * succeeded.
 *
 * <p>The sealed hierarchy distinguishes two states:
 * <ul>
 *   <li>{@link ResolvedTable} — the table was found in the jOOQ catalog; carries the jOOQ class
 *       name, the Java field name, and a flag indicating whether the table has a primary key.
 *       {@link ResolvedTable.Plain} represents a table whose GraphQL type has no {@code @node}
 *       directive; {@link ResolvedTable.WithNode} is the specialisation that also carries the
 *       {@code @node} directive properties ({@code typeId} and {@code keyColumns}) for Relay
 *       Global ID encoding.</li>
 *   <li>{@link UnresolvedTable} — the SQL table name could not be matched to any class in the
 *       jOOQ catalog. The
 *       {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports this as an error.</li>
 * </ul>
 */
public sealed interface TableRef permits TableRef.ResolvedTable, TableRef.UnresolvedTable {

    /** The raw SQL table name from the {@code @table} directive (e.g. {@code "film"}). */
    String tableName();

    /**
     * A {@link TableRef} where the jOOQ table class was successfully resolved from the catalog.
     *
     * <p>{@code javaClassName} is the simple class name of the generated jOOQ table class
     * (e.g. {@code "Film"}), taken directly from the live class via reflection. This respects any
     * custom jOOQ naming strategy.
     *
     * <p>{@code javaFieldName} is the field name in the generated jOOQ {@code Tables} class
     * (e.g. {@code "FILM"}). {@code hasPrimaryKey} is {@code true} when the jOOQ table has a
     * declared primary key, used for deterministic-ordering validation.
     *
     * <p>{@code primaryKeyColumnSqlNames} is the ordered list of SQL column names that form the
     * primary key (e.g. {@code ["language_id"]}), populated from
     * {@code table.getPrimaryKey().getFields()} at parse time. Empty when there is no primary key.
     * Used by the DataLoader data-fetcher generator to build the {@code DSL.row(...)} key
     * expression.
     *
     * <p>{@code primaryKeyColumnJavaTypes} is the parallel list of binary Java class names for
     * each primary-key column (e.g. {@code ["java.lang.Long"]} for a {@code BIGINT} column).
     * Populated from {@code field.getType().getName()} on each PK field at parse time.
     *
     * <p>Two specialisations exist:
     * <ul>
     *   <li>{@link Plain} — the owning GraphQL type has no {@code @node} directive.</li>
     *   <li>{@link WithNode} — the owning GraphQL type also carries {@code @node}; adds the
     *       optional {@code typeId} and the list of resolved key columns for Global ID encoding.</li>
     * </ul>
     */
    sealed interface ResolvedTable extends TableRef
        permits ResolvedTable.Plain, ResolvedTable.WithNode {

        String javaFieldName();
        String javaClassName();
        boolean hasPrimaryKey();
        List<String> primaryKeyColumnSqlNames();
        List<String> primaryKeyColumnJavaTypes();

        /**
         * Returns the single primary-key column SQL name (e.g. {@code "language_id"}).
         *
         * <p>Only valid when {@code hasPrimaryKey} is {@code true} and the PK is single-column,
         * as enforced by {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} for fields
         * that use the DataLoader pattern. Do not call when {@code primaryKeyColumnSqlNames} may
         * be empty or contain more than one element.
         */
        default String primaryKeyColumnSqlName() {
            return primaryKeyColumnSqlNames().get(0);
        }

        /**
         * Returns the binary Java class name of the single primary-key column
         * (e.g. {@code "java.lang.Long"} for a {@code BIGINT} column).
         *
         * <p>Only valid when {@code hasPrimaryKey} is {@code true} and the PK is single-column.
         * Do not call when {@code primaryKeyColumnJavaTypes} may be empty.
         */
        default String primaryKeyColumnJavaType() {
            return primaryKeyColumnJavaTypes().get(0);
        }

        /**
         * A resolved table whose owning GraphQL type has no {@code @node} directive.
         */
        record Plain(
            String tableName,
            String javaFieldName,
            String javaClassName,
            boolean hasPrimaryKey,
            List<String> primaryKeyColumnSqlNames,
            List<String> primaryKeyColumnJavaTypes
        ) implements ResolvedTable {}

        /**
         * A resolved table whose owning GraphQL type also carries a {@code @node} directive.
         * This is a specialisation of {@link ResolvedTable} that additionally carries the node
         * directive properties used for Relay Global ID encoding.
         *
         * <p>{@code typeId} is the value of the {@code typeId} argument on the {@code @node}
         * directive, or {@code null} when the argument was omitted.
         *
         * <p>{@code keyColumns} is the resolved list of {@code keyColumns} argument entries. Each
         * entry is either a {@link KeyColumnRef.ResolvedKeyColumn} (column found in the jOOQ table)
         * or a {@link KeyColumnRef.UnresolvedKeyColumn} (column name could not be matched). An empty
         * list means the argument was omitted, in which case the primary key is used at
         * code-generation time.
         */
        record WithNode(
            String tableName,
            String javaFieldName,
            String javaClassName,
            boolean hasPrimaryKey,
            List<String> primaryKeyColumnSqlNames,
            List<String> primaryKeyColumnJavaTypes,
            String typeId,
            List<KeyColumnRef> keyColumns
        ) implements ResolvedTable {}
    }

    /**
     * A {@link TableRef} where the SQL table name could not be matched to any class in the
     * jOOQ catalog.
     *
     * <p>The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports this as an error.
     */
    record UnresolvedTable(String tableName) implements TableRef {}
}
