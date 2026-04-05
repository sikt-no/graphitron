package no.sikt.graphitron.record.type;

/**
 * A field on a GraphQL input type, with the directive markers that generators need.
 *
 * <p>{@code typeName} is the base type name after unwrapping all {@code !} and {@code []}
 * wrappers. {@code nonNull} is {@code true} when the outermost type has a {@code !} suffix.
 * {@code list} is {@code true} when the type is wrapped in a list (after stripping the outer
 * {@code !} if present).
 *
 * <p>Built-in scalar names ({@code String}, {@code Int}, {@code Float}, {@code Boolean},
 * {@code ID}) are always valid. Any other {@code typeName} must resolve to a type in
 * {@link no.sikt.graphitron.record.GraphitronSchema#types()}.
 *
 * <p>{@code columnName} is the value of {@code @field(name:)} when present, otherwise the
 * GraphQL field name. {@code javaNamePresent} is {@code true} when {@code @field(javaName:)} was
 * present on this field; the value is not stored because {@code javaName} is deprecated and
 * generates a validation warning.
 */
public record InputFieldSpec(
    String name,
    String typeName,
    boolean nonNull,
    boolean list,
    boolean orderBy,
    String columnName,
    boolean javaNamePresent
) {}
