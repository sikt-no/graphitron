package no.sikt.graphitron.record.type;

/**
 * A {@link TableStep} where the SQL table name could not be matched to any class in the
 * jOOQ catalog.
 *
 * <p>The table name that failed to resolve is available on the parent record
 * (e.g. {@link TableType#tableName()}). The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports this as an error.
 */
public record UnresolvedTable() implements TableStep {}
