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
 *
 * <p>{@code node} captures whether a {@code @node} directive is present: {@link NoNode} when
 * absent, {@link NodeDirective} when present (carrying the optional {@code typeId} and the
 * list of key columns, each resolved against the jOOQ table via a {@link KeyColumnStep}).
 * {@code @node} is only permitted on types that also carry {@code @table}, which is why it
 * lives here rather than on a separate type variant. The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for each
 * {@link UnresolvedKeyColumn} in the list.
 */
public record TableType(
    String name,
    SourceLocation location,
    String tableName,
    TableStep table,
    NodeStep node
) implements GraphitronType {}
