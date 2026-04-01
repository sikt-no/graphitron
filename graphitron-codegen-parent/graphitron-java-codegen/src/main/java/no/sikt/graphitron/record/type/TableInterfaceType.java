package no.sikt.graphitron.record.type;

import graphql.language.SourceLocation;
import org.jooq.Table;

import java.util.Optional;

/**
 * An interface annotated with {@code @table} and {@code @discriminate}, where implementing
 * types have {@code @table} and {@code @discriminator}. Single-table interface pattern.
 */
public record TableInterfaceType(
    String name,
    SourceLocation location,
    String discriminatorColumn,
    String tableName,
    String javaFieldName,
    Optional<Table<?>> table
) implements GraphitronType {}
