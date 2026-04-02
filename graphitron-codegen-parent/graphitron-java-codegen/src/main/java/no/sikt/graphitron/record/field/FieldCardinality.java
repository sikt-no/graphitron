package no.sikt.graphitron.record.field;

/**
 * Cardinality of a field with a table-mapped, interface, or union return type.
 *
 * <p>Drives the shape of generated SQL and the resolver's return type:
 * <ul>
 *   <li>{@link Single} — 1:1 join; returns one {@code Record} (or null). No ordering.</li>
 *   <li>{@link List} — 1:N; returns {@code Result<Record>}. May carry a default sort order.</li>
 *   <li>{@link Connection} — Relay cursor-based pagination. Ordering rules follow {@link List}.</li>
 * </ul>
 */
public sealed interface FieldCardinality
    permits FieldCardinality.Single, FieldCardinality.List, FieldCardinality.Connection {

    /** 1:1 join — returns one {@code Record} (or null). No ordering. */
    record Single() implements FieldCardinality {}

    /**
     * 1:N — returns {@code Result<Record>}. May carry a default sort order; query fields also
     * carry the {@code @orderBy} enum value specs (empty list for child fields).
     *
     * <p>{@code defaultOrder} is {@code null} when the {@code @defaultOrder} directive is absent.
     * The validator reports an error if the spec contains an unresolved index or primary key.
     */
    record List(
        DefaultOrderSpec defaultOrder,
        java.util.List<OrderByEnumValueSpec> orderByValues
    ) implements FieldCardinality {}

    /**
     * Relay cursor-based paginated list. Cursor/pagination config is TBD — this variant
     * will gain additional components (cursor column, totalCount flag, etc.) when connection
     * support is implemented. Ordering rules follow {@link List}.
     */
    record Connection(
        DefaultOrderSpec defaultOrder,
        java.util.List<OrderByEnumValueSpec> orderByValues
    ) implements FieldCardinality {}
}
