package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

/**
 * A resolved column in a jOOQ table.
 *
 * <p>{@code sqlName} is the SQL column name as it appears in the database (e.g. {@code "film_id"}).
 * {@code javaName} is the Java field name in the jOOQ table class (e.g. {@code "FILM_ID"}).
 * {@code columnClass} is the raw Java class name of the column type as jOOQ reports it via
 * {@code Field.getType().getName()}. For a scalar column this is a source-form FQCN
 * (e.g. {@code "java.lang.Integer"}); for an array-typed column it is the JVM <em>binary</em>
 * descriptor (e.g. {@code "[Ljava.lang.Boolean;"}). It is retained in binary form because
 * consumers such as {@code EnumMappingResolver} ({@code Class.forName}) and
 * {@code SourceRowDirectiveResolver} ({@code Class.getName()} compares) depend on that form.
 *
 * <p>{@code columnType} is the same fact decided once at the catalog boundary via
 * {@code TypeName.get(col.getType())}: a {@link no.sikt.graphitron.javapoet.ClassName} for a scalar
 * column, an {@link no.sikt.graphitron.javapoet.ArrayTypeName} for an array column. Codegen sites
 * emit this directly rather than re-parsing {@code columnClass} with {@code ClassName.bestGuess},
 * which rejects array descriptors (R446). It is a denormalised view of {@code columnClass} (both
 * derive from the same live {@code Class}), carried because that {@code Class} is only available at
 * the boundary.
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
public record ColumnRef(String sqlName, String javaName, String columnClass, TypeName columnType) {
    /**
     * Convenience for hand-built refs that only have the source-form {@code columnClass} string and
     * no live {@code Class} to decode (test fixtures, and any non-boundary construction): derives
     * the denormalised {@code columnType} from {@code columnClass} via {@code ClassName.bestGuess}.
     *
     * <p>Scalar columns only. The catalog boundary ({@code JooqCatalog}) is the sole array-safe
     * producer: it supplies {@code TypeName.get(col.getType())}, which decodes array descriptors
     * natively. Passing an array descriptor here would hit the very {@code bestGuess} rejection the
     * boundary type-lift exists to avoid, which is intentional: array columns must not be
     * hand-constructed.
     */
    public ColumnRef(String sqlName, String javaName, String columnClass) {
        this(sqlName, javaName, columnClass, bestGuessScalarTypeOrNull(columnClass));
    }

    /**
     * The single decode shared by {@link ColumnRef} and {@code JooqCatalog.ColumnEntry}'s
     * hand-built (3-/4-arg) constructors, so the two never diverge on placeholder tolerance. A real
     * scalar FQCN decodes to a {@link ClassName}; the synthetic placeholder values some fixtures
     * pass for {@code columnClass} (an empty string, a key name, a {@code related_n} tag) are not
     * class names and yield a {@code null} {@code columnType}. Such refs exist only for their
     * {@code sqlName}/{@code javaName} and are never emitted, so their type is never read. Array
     * columns never reach here (see the constructor javadoc); the array-safe producer is the catalog
     * reflection boundary, which supplies {@code TypeName.get(col.getType())}.
     */
    public static TypeName bestGuessScalarTypeOrNull(String columnClass) {
        if (columnClass == null || columnClass.isBlank()) return null;
        try {
            return ClassName.bestGuess(columnClass);
        } catch (IllegalArgumentException notAClassName) {
            return null;
        }
    }
}
