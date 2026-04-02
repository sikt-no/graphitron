package no.sikt.graphitron.record.field;

import org.jooq.Field;

/**
 * A {@link ColumnStep} where the column was successfully resolved in the jOOQ table.
 *
 * <p>{@code javaName} is the Java field name in the jOOQ table class (e.g. {@code "TITLE"}
 * for {@code FILM.TITLE}). {@code column} is the resolved jOOQ {@link Field} instance, used
 * for type inspection at code-generation time.
 */
public record ResolvedColumn(String javaName, Field<?> column) implements ColumnStep {}
