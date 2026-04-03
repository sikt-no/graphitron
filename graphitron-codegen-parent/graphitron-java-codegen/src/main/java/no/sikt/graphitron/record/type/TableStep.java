package no.sikt.graphitron.record.type;

/**
 * Represents the outcome of resolving a {@code @table} directive value to a jOOQ table class.
 *
 * <p>The sealed hierarchy distinguishes two states:
 * <ul>
 *   <li>{@link ResolvedTable} — the table was found in the jOOQ catalog; carries the Java field
 *       name and the resolved {@link org.jooq.Table} instance.</li>
 *   <li>{@link UnresolvedTable} — the SQL table name could not be matched to any class in the
 *       jOOQ catalog. The table name is available on the parent record (e.g.
 *       {@link GraphitronType.TableType#tableName()}). The
 *       {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports this as an error.</li>
 * </ul>
 */
public sealed interface TableStep permits TableStep.ResolvedTable, TableStep.UnresolvedTable {

    /**
     * A {@link TableStep} where the jOOQ table class was successfully resolved from the catalog.
     *
     * <p>{@code javaFieldName} is the field name in the generated jOOQ {@code Tables} class
     * (e.g. {@code "FILM"}). {@code table} is the resolved jOOQ {@link org.jooq.Table} instance,
     * used for column and FK metadata at code-generation time.
     */
    record ResolvedTable(String javaFieldName, org.jooq.Table<?> table) implements TableStep {}

    /**
     * A {@link TableStep} where the SQL table name could not be matched to any class in the
     * jOOQ catalog.
     *
     * <p>The table name that failed to resolve is available on the parent record
     * (e.g. {@link GraphitronType.TableType#tableName()}). The
     * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports this as an error.
     */
    record UnresolvedTable() implements TableStep {}
}
