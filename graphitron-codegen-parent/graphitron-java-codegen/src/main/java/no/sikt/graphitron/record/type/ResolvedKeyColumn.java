package no.sikt.graphitron.record.type;

/**
 * A {@link KeyColumnStep} where the column was successfully resolved in the jOOQ table.
 *
 * <p>{@code name} is the SQL column name as written in the directive (e.g. {@code "film_id"}).
 * {@code javaName} is the Java field name in the jOOQ table class (e.g. {@code "FILM_ID"}).
 */
public record ResolvedKeyColumn(String name, String javaName) implements KeyColumnStep {}
