package no.sikt.graphitron.rewrite.type;

/**
 * Represents the outcome of resolving one {@code keyColumns} entry from {@code @node}
 * against the jOOQ table.
 *
 * <p>The sealed hierarchy distinguishes two states:
 * <ul>
 *   <li>{@link ResolvedKeyColumn} — the column was found in the jOOQ table; carries both
 *       the SQL name from the directive and the Java field name used in generated code.</li>
 *   <li>{@link UnresolvedKeyColumn} — the column name could not be matched to any field in
 *       the jOOQ table. The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator}
 *       reports this as an error.</li>
 * </ul>
 */
public sealed interface KeyColumnRef permits KeyColumnRef.ResolvedKeyColumn, KeyColumnRef.UnresolvedKeyColumn {

    /**
     * A {@link KeyColumnRef} where the column was successfully resolved in the jOOQ table.
     *
     * <p>{@code name} is the SQL column name as written in the directive (e.g. {@code "film_id"}).
     * {@code javaName} is the Java field name in the jOOQ table class (e.g. {@code "FILM_ID"}).
     */
    record ResolvedKeyColumn(String name, String javaName) implements KeyColumnRef {}

    /**
     * A {@link KeyColumnRef} where the SQL column name could not be matched to any field in
     * the jOOQ table.
     *
     * <p>{@code name} is the SQL column name as written in the directive that failed to resolve.
     * The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports this as an error.
     */
    record UnresolvedKeyColumn(String name) implements KeyColumnRef {}
}
