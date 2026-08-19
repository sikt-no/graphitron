package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ArrayTypeName;
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
 * which rejects array descriptors. It is a denormalised view of {@code columnClass} (both
 * derive from the same live {@code Class}), carried because that {@code Class} is only available at
 * the boundary.
 *
 * <p>Used wherever a column reference is needed: output field columns
 * ({@link no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedField},
 * {@link no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedReferenceField}) and
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
     * {@code columnType} via {@link #bestGuessScalarTypeOrNull}.
     *
     * <p>Scalar columns only. {@code ClassName.bestGuess} rejects array descriptors, intentionally:
     * array columns must not be hand-constructed. The catalog boundary ({@code JooqCatalog}) is the
     * sole array-safe producer, supplying {@code TypeName.get(col.getType())}.
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
     * columns never reach here (see the constructor javadoc).
     */
    public static TypeName bestGuessScalarTypeOrNull(String columnClass) {
        if (columnClass == null || columnClass.isBlank()) return null;
        try {
            return ClassName.bestGuess(columnClass);
        } catch (IllegalArgumentException notAClassName) {
            return null;
        }
    }

    /**
     * Decodes a captured binding type into its javapoet form, arrays included. The third way to get a
     * {@code columnType} right, beside carrying a sibling record's decoded one and decoding a live
     * {@code Class} at the reflection boundary, and the one a store-sourced reader needs: it holds a
     * name and no class, the codegen loader being closed by the time it runs.
     *
     * <p>Array-safe because the captured name is the raw {@code Class.getName()} form, which spells an
     * array as a JVM descriptor ({@code [Ljava.lang.Boolean;}) and is therefore fully recoverable.
     * That descriptor is exactly what crashes {@link ClassName#bestGuess}, which is why the scalar
     * decoder above must not be reached for a column and why a guard test forbids the constructor that
     * would: a boolean-array column is an ordinary column in a consumer's database, and a generator
     * that dies on one is broken rather than unlucky.
     *
     * @param bindingType the captured {@code sql_column.binding_type}, a source-form FQCN for a
     *                    scalar and a JVM descriptor for an array
     * @throws IllegalArgumentException on a name that decodes to neither, which is capture drift
     *                                  rather than a shape a consumer's catalog can produce
     */
    public static TypeName decodeBindingType(String bindingType) {
        if (bindingType == null || bindingType.isBlank()) {
            throw new IllegalArgumentException(
                "a captured column carries a binding type; a blank one is capture drift");
        }
        if (bindingType.startsWith("[")) {
            return ArrayTypeName.of(decodeBindingType(componentOf(bindingType)));
        }
        var primitive = PRIMITIVE_DESCRIPTORS.get(bindingType);
        return primitive != null ? primitive : ClassName.bestGuess(bindingType);
    }

    /**
     * One array dimension stripped: {@code [Ljava.lang.Boolean;} to {@code java.lang.Boolean}, and
     * {@code [[I} to {@code [I}, so a nested array recurses one level per call.
     */
    private static String componentOf(String descriptor) {
        String component = descriptor.substring(1);
        if (component.startsWith("L") && component.endsWith(";")) {
            return component.substring(1, component.length() - 1);
        }
        return component;
    }

    /**
     * The JVM's single-letter primitive descriptors, which appear only inside an array: a primitive
     * column is boxed by the time jOOQ names its binding type, but a primitive <em>array</em> keeps
     * its element descriptor.
     */
    private static final java.util.Map<String, TypeName> PRIMITIVE_DESCRIPTORS = java.util.Map.of(
        "Z", TypeName.BOOLEAN, "B", TypeName.BYTE, "C", TypeName.CHAR, "S", TypeName.SHORT,
        "I", TypeName.INT, "J", TypeName.LONG, "F", TypeName.FLOAT, "D", TypeName.DOUBLE);
}
