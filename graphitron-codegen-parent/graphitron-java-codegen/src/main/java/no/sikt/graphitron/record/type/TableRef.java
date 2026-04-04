package no.sikt.graphitron.record.type;

/**
 * Represents the outcome of resolving a {@code @table} directive value to a jOOQ table class.
 *
 * <p>Both implementations carry the raw SQL table name ({@link #tableName()}) that was used in
 * the resolution attempt, so callers always have access to it regardless of whether resolution
 * succeeded.
 *
 * <p>The sealed hierarchy distinguishes two states:
 * <ul>
 *   <li>{@link ResolvedTable} — the table was found in the jOOQ catalog; carries the Java field
 *       name and the resolved {@link org.jooq.Table} instance.</li>
 *   <li>{@link UnresolvedTable} — the SQL table name could not be matched to any class in the
 *       jOOQ catalog. The
 *       {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports this as an error.</li>
 * </ul>
 */
public sealed interface TableRef permits TableRef.ResolvedTable, TableRef.UnresolvedTable {

    /** The raw SQL table name from the {@code @table} directive (e.g. {@code "film"}). */
    String tableName();

    /**
     * A {@link TableRef} where the jOOQ table class was successfully resolved from the catalog.
     *
     * <p>{@code javaFieldName} is the field name in the generated jOOQ {@code Tables} class
     * (e.g. {@code "FILM"}). {@code table} is the resolved jOOQ {@link org.jooq.Table} instance,
     * used for column and FK metadata at code-generation time.
     */
    record ResolvedTable(String tableName, String javaFieldName, org.jooq.Table<?> table) implements TableRef {}

    /**
     * A {@link TableRef} where the SQL table name could not be matched to any class in the
     * jOOQ catalog.
     *
     * <p>The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports this as an error.
     */
    record UnresolvedTable(String tableName) implements TableRef {}
}
