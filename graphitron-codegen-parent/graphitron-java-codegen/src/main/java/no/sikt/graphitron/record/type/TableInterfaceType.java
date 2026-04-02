package no.sikt.graphitron.record.type;

import graphql.language.SourceLocation;

/**
 * An interface annotated with {@code @table} and {@code @discriminate}, where implementing
 * types have {@code @table} and {@code @discriminator}. Single-table interface pattern.
 *
 * <p>{@code tableName} is the SQL name from the directive.
 *
 * <p>{@code table} is the outcome of resolving {@code tableName} against the jOOQ catalog:
 * {@link ResolvedTable} when the table was found, {@link UnresolvedTable} when it was not. The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for
 * {@code UnresolvedTable}.
 */
public record TableInterfaceType(
    String name,
    SourceLocation location,
    String discriminatorColumn,
    String tableName,
    TableStep table
) implements GraphitronType {}
