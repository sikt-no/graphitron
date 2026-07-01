package no.sikt.graphitron.rewrite.model;

/**
 * Groups the four Relay pagination arguments that may be present on a list/connection field.
 *
 * <p>Each of the four Relay cursor-pagination arguments ({@code first}, {@code last},
 * {@code after}, {@code before}) is represented as a nullable {@link PaginationArg}. A field
 * that carries none of these arguments has a {@code null} {@link PaginationSpec} in the model
 * (not a {@link PaginationSpec} with all-null components).
 *
 * <p>Exactly which arguments are present depends on the schema: forward-only pagination may
 * provide only {@code first} and {@code after}; bidirectional pagination provides all four.
 *
 * <p>{@link PaginationArg} carries the GraphQL scalar type and non-null flag. The argument's
 * GraphQL name is fixed by its slot ({@code first}/{@code last}/{@code after}/{@code before});
 * the classifier rejects custom names, so the model never carries one. The {@code list} flag is
 * not needed, pagination arguments are always scalars.
 */
public record PaginationSpec(
    PaginationArg first,
    PaginationArg last,
    PaginationArg after,
    PaginationArg before
) {

    /**
     * Metadata for one Relay pagination argument.
     *
     * <p>{@code typeName} is the GraphQL scalar type (typically {@code "Int"} for first/last,
     * {@code "String"} for after/before).
     * {@code nonNull} reflects whether the argument type has a {@code !} wrapper.
     */
    public record PaginationArg(String typeName, boolean nonNull) {}
}
