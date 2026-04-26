package no.sikt.graphitron.rewrite.model;

/**
 * The wrapper applied to a field's element return type in the GraphQL schema.
 *
 * <p>Describes the cardinality and nullability of the return — not the ordering.
 * Ordering is held separately on each field as an {@link OrderBySpec}. That design makes
 * ordering authoritative and independent of whether the result is a list or a connection.
 *
 * <p>Three variants:
 * <ul>
 *   <li>{@link Single} — the field returns at most one element; {@code nullable} reflects
 *       whether the whole value may be absent.</li>
 *   <li>{@link List} — the field returns a GraphQL list; {@code listNullable} reflects
 *       whether the list itself may be null, {@code itemNullable} reflects whether individual
 *       items may be null.</li>
 *   <li>{@link Connection} — the field returns a Relay connection (detected structurally via
 *       the {@code edges.node} pattern); nullability flags follow the same convention as
 *       {@link List}.</li>
 * </ul>
 */
public sealed interface FieldWrapper
        permits FieldWrapper.Single, FieldWrapper.List, FieldWrapper.Connection {

    /**
     * Fallback page size used when a {@code @asConnection} field omits
     * {@code defaultFirstValue}. Referenced by {@link FieldWrapper.Connection}'s
     * structural-detection constructor and by the classifier's default-page-size
     * resolution, so all four fallback sites agree on the same literal.
     */
    int DEFAULT_PAGE_SIZE = 100;

    /** Returns {@code true} for {@link List} and {@link Connection}, {@code false} for {@link Single}. */
    default boolean isList() { return !(this instanceof Single); }

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
     */
    record List(
        boolean listNullable,
        boolean itemNullable
    ) implements FieldWrapper {}

    /**
     * The field returns a Relay cursor-paginated connection. The per-type metadata
     * (Connection name, element type name, item nullability) lives on the first-class
     * {@link no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType} entry in
     * {@link no.sikt.graphitron.rewrite.GraphitronSchema#types()}; this wrapper carries only
     * the per-carrier-site information that varies independently of the Connection type.
     *
     * <p>{@code connectionNullable} is {@code true} when the connection wrapper itself may be
     * null at this carrier site.
     *
     * <p>{@code defaultPageSize} is the default page size when the client omits the {@code first}
     * argument (from {@code @asConnection(defaultFirstValue:)}). Defaults to 100. Per-site
     * because two carriers returning the same Connection type may declare different defaults.
     */
    record Connection(
        boolean connectionNullable,
        int defaultPageSize
    ) implements FieldWrapper {}
}
