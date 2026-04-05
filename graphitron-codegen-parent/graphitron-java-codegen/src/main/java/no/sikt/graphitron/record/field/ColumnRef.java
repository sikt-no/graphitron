package no.sikt.graphitron.record.field;

import org.jooq.Field;

/**
 * Represents the outcome of resolving a GraphQL field to a jOOQ column.
 *
 * <p>The sealed hierarchy distinguishes two states:
 * <ul>
 *   <li>{@link ResolvedColumn} — the column was found in the jOOQ table; carries the Java field
 *       name and the resolved {@link org.jooq.Field} instance.</li>
 *   <li>{@link UnresolvedColumn} — the column name could not be matched to any field in the
 *       jOOQ table. The column name is available on the parent record (e.g.
 *       {@link ChildField.ColumnField#columnName()}). The
 *       {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports this as an error.</li>
 * </ul>
 */
public sealed interface ColumnRef permits ColumnRef.ResolvedColumn, ColumnRef.UnresolvedColumn {

    /**
     * A {@link ColumnRef} where the column was successfully resolved in the jOOQ table.
     *
     * <p>{@code javaName} is the Java field name in the jOOQ table class (e.g. {@code "TITLE"}
     * for {@code FILM.TITLE}). {@code column} is the resolved jOOQ {@link Field} instance, used
     * for type inspection at code-generation time.
     */
    record ResolvedColumn(String javaName, Field<?> column) implements ColumnRef {}

    /**
     * A {@link ColumnRef} where the column name could not be matched to any field in the jOOQ table.
     *
     * <p>The column name that failed to resolve is available on the parent record
     * (e.g. {@link ChildField.ColumnField#columnName()}). The
     * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports this as an error.
     */
    record UnresolvedColumn() implements ColumnRef {}
}
