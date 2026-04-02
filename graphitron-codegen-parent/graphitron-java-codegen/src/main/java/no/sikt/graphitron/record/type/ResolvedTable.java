package no.sikt.graphitron.record.type;

import org.jooq.Table;

/**
 * A {@link TableStep} where the jOOQ table class was successfully resolved from the catalog.
 *
 * <p>{@code javaFieldName} is the field name in the generated jOOQ {@code Tables} class
 * (e.g. {@code "FILM"}). {@code table} is the resolved jOOQ {@link Table} instance, used for
 * column and FK metadata at code-generation time.
 */
public record ResolvedTable(String javaFieldName, Table<?> table) implements TableStep {}
