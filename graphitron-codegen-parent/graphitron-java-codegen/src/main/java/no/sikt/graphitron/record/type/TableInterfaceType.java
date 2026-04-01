package no.sikt.graphitron.record.type;

import graphql.schema.GraphQLInterfaceType;
import org.jooq.Table;

import java.util.Optional;

/**
 * An interface annotated with {@code @table} and {@code @discriminate}, where implementing
 * types have {@code @table} and {@code @discriminator}. Single-table interface pattern.
 */
public record TableInterfaceType(
    GraphQLInterfaceType definition,
    String discriminatorColumn,
    String tableName,
    String javaFieldName,
    Optional<Table<?>> table
) implements GraphitronType {}
