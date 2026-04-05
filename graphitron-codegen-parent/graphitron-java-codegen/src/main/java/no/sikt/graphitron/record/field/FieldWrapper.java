package no.sikt.graphitron.record.field;

/**
 * The wrapper applied to a field's element return type in the GraphQL schema.
 *
 * <p>In GraphQL, any type can be list-wrapped ({@code [T]}) and non-null-wrapped ({@code T!}).
 * The Relay Connection convention adds a third wrapper kind ({@code TConnection}) with the same
 * semantic purpose as a list but with cursor-based pagination. All three are modelled here as
 * wrappers around a single element type, which is always what {@link ReturnTypeRef} carries:
 * <ul>
 *   <li>{@link Single} — the field returns one instance of the element type (or null).</li>
 *   <li>{@link List} — the field returns a SQL-style list of element instances.</li>
 *   <li>{@link Connection} — the field returns a Relay cursor-paginated collection. The element
 *       type is resolved via {@code edges.node} in the schema rather than by stripping a name
 *       suffix, since the schema structure is the authoritative definition.</li>
 * </ul>
 *
 * <p>Nullability is tracked at both the wrapper level and the item level. Both correspond to
 * positions in the GraphQL type expression: for {@link List} the expression is
 * {@code [Item]Wrapper} where each position may carry {@code !}; for {@link Connection} the
 * same positions apply to the connection wrapper and the {@code edges.node} type respectively.
 *
 * <p>Ordering configuration ({@link DefaultOrderSpec}, {@link OrderByEnumValueSpec}) is carried
 * on {@link List} and {@link Connection} only, as {@link Single} implies no ordering concern.
 */
public sealed interface FieldWrapper
    permits FieldWrapper.Single, FieldWrapper.List, FieldWrapper.Connection {

    /**
     * The field returns a single instance of the element type.
     *
     * <p>{@code nullable} is {@code true} when the GraphQL type expression has no {@code !} on
     * the return type (the common default).
     */
    record Single(boolean nullable) implements FieldWrapper {}

    /**
     * The field returns a list of element instances ({@code [Item]}).
     *
     * <p>{@code listNullable} is {@code true} when the list itself may be null (no {@code !} on
     * the outer wrapper). {@code itemNullable} is {@code true} when individual items may be null
     * (no {@code !} on the inner type).
     *
     * <p>{@code defaultOrder} is {@code null} when {@code @defaultOrder} is absent.
     * {@code orderByValues} carries the {@code @orderBy} enum value specs; always empty for
     * child fields (populated only at the query-field level).
     */
    record List(
        boolean listNullable,
        boolean itemNullable,
        DefaultOrderSpec defaultOrder,
        java.util.List<OrderByEnumValueSpec> orderByValues
    ) implements FieldWrapper {}

    /**
     * The field returns a Relay cursor-paginated connection.
     *
     * <p>The element type is the type of the {@code edges.node} field in the schema — not derived
     * from the connection type name. That type is what {@link ReturnTypeRef} carries.
     *
     * <p>{@code connectionNullable} is {@code true} when the connection wrapper itself may be
     * null. {@code itemNullable} is {@code true} when individual {@code edges.node} items may be
     * null.
     *
     * <p>Ordering fields follow the same semantics as {@link List}.
     */
    record Connection(
        boolean connectionNullable,
        boolean itemNullable,
        DefaultOrderSpec defaultOrder,
        java.util.List<OrderByEnumValueSpec> orderByValues
    ) implements FieldWrapper {}
}
