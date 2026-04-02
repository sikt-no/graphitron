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
 *       {@link TableType#tableName()}). The
 *       {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports this as an error.</li>
 * </ul>
 */
public sealed interface TableStep permits ResolvedTable, UnresolvedTable {}
