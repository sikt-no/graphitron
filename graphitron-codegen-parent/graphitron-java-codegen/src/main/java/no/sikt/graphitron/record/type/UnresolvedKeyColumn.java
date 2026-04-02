package no.sikt.graphitron.record.type;

/**
 * A {@link KeyColumnStep} where the SQL column name could not be matched to any field in
 * the jOOQ table.
 *
 * <p>{@code name} is the SQL column name as written in the directive that failed to resolve.
 * The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports this as an error.
 */
public record UnresolvedKeyColumn(String name) implements KeyColumnStep {}
