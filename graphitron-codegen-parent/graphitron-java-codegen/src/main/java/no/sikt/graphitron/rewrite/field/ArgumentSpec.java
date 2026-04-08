package no.sikt.graphitron.rewrite.field;

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
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema#types()}.
 *
 * <p>When the enclosing field is a {@link no.sikt.graphitron.rewrite.field.QueryField.LookupQueryField},
 * all arguments — regardless of whether {@code @lookupKey} appeared on them — participate equally
 * in the lookup semantics (list args are positionally correlated; scalar args are broadcast).
 * {@code @lookupKey} is a field-level classifier only: its presence on any argument in the arg
 * tree causes the field to be classified as a {@code LookupQueryField}. There is no per-argument
 * semantic distinction.
 *
 * <p>{@code conditionArg} is {@code true} when the {@code @condition} directive is present on
 * this argument. The builder converts this to an {@link ArgumentRef.UnclassifiedArg} since
 * {@code @condition} is only supported on field definitions.
 *
 * <p>{@code lookupKey} is {@code true} when the {@code @lookupKey} directive is present on
 * this argument. On a {@link no.sikt.graphitron.rewrite.field.ChildField.TableField} with
 * {@code splitQuery = true} this marks the field as a result mapped LookupTableField, which
 * blocks field-level {@code @condition} and connection-cardinality returns.
 *
 * <p>{@code columnName} is the value of {@code @field(name:)} when present, otherwise the
 * GraphQL argument name. Used to identify which database column this argument maps to.
 */
public record ArgumentSpec(
    String name,
    String typeName,
    boolean nonNull,
    boolean list,
    boolean orderBy,
    boolean conditionArg,
    boolean lookupKey,
    String columnName
) {}
