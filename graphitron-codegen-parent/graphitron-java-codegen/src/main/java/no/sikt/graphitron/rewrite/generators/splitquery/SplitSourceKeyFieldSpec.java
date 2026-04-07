package no.sikt.graphitron.rewrite.generators.splitquery;

/**
 * Per-column data for the {@code rows} derived-source method.
 *
 * <p>{@code columnJavaName} is the Java field name in the jOOQ parent table class
 * (e.g. {@code "LANGUAGE_ID"}), used to reference {@code PARENT_TABLE.COLUMN} in the generated
 * record construction and for extracting values from source records via
 * {@code sources.get(i).get(TABLE.COLUMN)}.
 *
 * <p>{@code columnClass} is the fully qualified Java class name of the column type
 * (e.g. {@code "java.lang.Integer"}), used in the return-type declaration.
 */
public record SplitSourceKeyFieldSpec(
    String columnJavaName,
    String columnClass
) {}
