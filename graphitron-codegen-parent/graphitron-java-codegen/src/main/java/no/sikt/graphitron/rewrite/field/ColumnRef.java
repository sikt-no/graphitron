package no.sikt.graphitron.rewrite.field;

/**
 * Represents the outcome of resolving a GraphQL field to a jOOQ column.
 *
 * <p>The sealed hierarchy has one state:
 * <ul>
 *   <li>{@link ResolvedColumn} — the column was found in the jOOQ table; carries the Java field
 *       name and the fully qualified column type class name.</li>
 * </ul>
 *
 * <p>When a column cannot be resolved, the containing field is classified as
 * {@link no.sikt.graphitron.rewrite.field.GraphitronField.UnclassifiedField} at build time rather
 * than carrying an unresolved state in the component.
 */
public sealed interface ColumnRef permits ColumnRef.ResolvedColumn {

    /**
     * A {@link ColumnRef} where the column was successfully resolved in the jOOQ table.
     *
     * <p>{@code javaName} is the Java field name in the jOOQ table class (e.g. {@code "TITLE"}
     * for {@code FILM.TITLE}). {@code columnClass} is the fully qualified Java class name of the
     * column type (e.g. {@code "java.lang.String"}).
     */
    record ResolvedColumn(String javaName, String columnClass) implements ColumnRef {}
}
