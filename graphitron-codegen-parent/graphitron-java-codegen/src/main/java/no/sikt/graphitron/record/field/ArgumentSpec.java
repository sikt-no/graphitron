package no.sikt.graphitron.record.field;

/**
 * A single argument on a GraphQL field, with the directive markers that generators need.
 *
 * <p>{@code typeName} is the base type name after unwrapping all {@code !} and {@code []} wrappers.
 * {@code nonNull} is {@code true} when the outermost type has a {@code !} suffix.
 * {@code list} is {@code true} when the type is wrapped in a list (after stripping the outer
 * {@code !} if present).
 *
 * <p>Built-in scalar names ({@code String}, {@code Int}, {@code Float}, {@code Boolean},
 * {@code ID}) are always valid. Any other {@code typeName} must resolve to a type in
 * {@link no.sikt.graphitron.record.GraphitronSchema#types()}.
 *
 * <p>{@code conditionArg} is {@code true} when the {@code @condition} directive is present on
 * this argument. Resolution of the condition method is deferred.
 */
public record ArgumentSpec(
    String name,
    String typeName,
    boolean nonNull,
    boolean list,
    boolean lookupKey,
    boolean orderBy,
    boolean conditionArg
) {}
