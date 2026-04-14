package no.sikt.graphitron.rewrite.model;

/**
 * A resolved column in a jOOQ table.
 *
 * <p>{@code sqlName} is the SQL column name as it appears in the database (e.g. {@code "film_id"}).
 * {@code javaName} is the Java field name in the jOOQ table class (e.g. {@code "FILM_ID"}).
 * {@code columnClass} is the fully qualified Java class name of the column type
 * (e.g. {@code "java.lang.Integer"}).
 *
 * <p>Used wherever a column reference is needed — both for output field columns
 * ({@link no.sikt.graphitron.rewrite.model.ChildField.ColumnField},
 * {@link no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField}) and for
 * {@code @node} key columns ({@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType}).
 *
 * <p>When a column cannot be resolved the containing field or type is classified as
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} or
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType} at build time.
 */
public record ColumnRef(String sqlName, String javaName, String columnClass) {}
