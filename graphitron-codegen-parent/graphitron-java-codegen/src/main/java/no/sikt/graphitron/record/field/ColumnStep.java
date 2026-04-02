package no.sikt.graphitron.record.field;

/**
 * Represents the outcome of resolving a GraphQL field to a jOOQ column.
 *
 * <p>The sealed hierarchy distinguishes two states:
 * <ul>
 *   <li>{@link ResolvedColumn} — the column was found in the jOOQ table; carries the Java field
 *       name and the resolved {@link org.jooq.Field} instance.</li>
 *   <li>{@link UnresolvedColumn} — the column name could not be matched to any field in the
 *       jOOQ table. The column name is available on the parent record (e.g.
 *       {@link ColumnField#columnName()}).  The {@link no.sikt.graphitron.record.GraphitronSchemaValidator}
 *       reports this as an error.</li>
 * </ul>
 */
public sealed interface ColumnStep permits ResolvedColumn, UnresolvedColumn {}
