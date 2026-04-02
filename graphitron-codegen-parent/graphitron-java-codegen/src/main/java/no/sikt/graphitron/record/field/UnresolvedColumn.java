package no.sikt.graphitron.record.field;

/**
 * A {@link ColumnStep} where the column name could not be matched to any field in the jOOQ table.
 *
 * <p>The column name that failed to resolve is available on the parent record
 * (e.g. {@link ColumnField#columnName()}). The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports this as an error.
 */
public record UnresolvedColumn() implements ColumnStep {}
