package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;
import org.jooq.Field;

import java.util.Optional;

/**
 * A scalar or enum field bound to a column on the source table.
 *
 * <p>{@code columnName} is the database column name: the value of {@code @field(name:)} when
 * the directive is present, otherwise the GraphQL field name.
 *
 * <p>{@code columnJavaName} is the Java field name in the jOOQ table class (e.g. {@code "TITLE"}
 * for {@code FILM.TITLE}); {@code null} when the column could not be resolved in the jOOQ catalog.
 *
 * <p>{@code column} is the resolved jOOQ {@link Field} instance, used for type inspection at
 * code-generation time; empty when the column could not be resolved.
 */
public record ColumnField(
    String name,
    SourceLocation location,
    String columnName,
    String columnJavaName,
    Optional<Field<?>> column
) implements ChildField {}
