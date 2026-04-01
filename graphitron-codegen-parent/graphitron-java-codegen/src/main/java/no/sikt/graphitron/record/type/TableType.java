package no.sikt.graphitron.record.type;

import graphql.language.SourceLocation;
import org.jooq.Table;

import java.util.Optional;

/**
 * A type annotated with {@code @table}. Full SQL generation applies.
 * {@code tableName} is the SQL name from the directive; {@code javaFieldName} is the
 * corresponding field name in the generated jOOQ {@code Tables} class (e.g. {@code "FILM"}).
 * {@code table} is empty if the jOOQ class could not be resolved.
 */
public record TableType(
    String name,
    SourceLocation location,
    String tableName,
    String javaFieldName,
    Optional<Table<?>> table
) implements GraphitronType {}
