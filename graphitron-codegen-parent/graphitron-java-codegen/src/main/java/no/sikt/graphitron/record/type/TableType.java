package no.sikt.graphitron.record.type;

import graphql.language.SourceLocation;

/**
 * A type annotated with {@code @table}. Full SQL generation applies.
 *
 * <p>{@code tableName} is the SQL name from the directive (e.g. {@code "film"}).
 *
 * <p>{@code table} is the outcome of resolving {@code tableName} against the jOOQ catalog:
 * {@link ResolvedTable} when the table was found (carrying the Java field name and the jOOQ
 * {@link org.jooq.Table} instance), {@link UnresolvedTable} when it was not. The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for
 * {@code UnresolvedTable}.
 */
public record TableType(
    String name,
    SourceLocation location,
    String tableName,
    TableStep table
) implements GraphitronType {}
