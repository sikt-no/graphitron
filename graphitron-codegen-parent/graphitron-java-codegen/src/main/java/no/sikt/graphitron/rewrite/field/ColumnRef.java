package no.sikt.graphitron.rewrite.field;

/**
 * Represents the outcome of resolving a GraphQL field to a jOOQ column.
 *
 * <p>The sealed hierarchy distinguishes two states:
 * <ul>
 *   <li>{@link ResolvedColumn} — the column was found in the jOOQ table; carries the Java field
 *       name and the fully qualified column type class name.</li>
 *   <li>{@link UnresolvedColumn} — the column name could not be matched to any field in the
 *       jOOQ table. The column name is available on the parent record (e.g.
 *       {@link ChildField.ColumnField#columnName()}). The
 *       {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports this as an error.</li>
 * </ul>
 */
public sealed interface ColumnRef permits ColumnRef.ResolvedColumn, ColumnRef.UnresolvedColumn {

    /**
     * A {@link ColumnRef} where the column was successfully resolved in the jOOQ table.
     *
     * <p>{@code javaName} is the Java field name in the jOOQ table class (e.g. {@code "TITLE"}
     * for {@code FILM.TITLE}). {@code columnClass} is the fully qualified Java class name of the
     * column type (e.g. {@code "java.lang.String"}).
     */
    record ResolvedColumn(String javaName, String columnClass) implements ColumnRef {}

    /**
     * A {@link ColumnRef} where the column name could not be matched to any field in the jOOQ table.
     *
     * <p>The column name that failed to resolve is available on the parent record
     * (e.g. {@link ChildField.ColumnField#columnName()}). The
     * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports this as an error.
     */
    record UnresolvedColumn() implements ColumnRef {}
}
