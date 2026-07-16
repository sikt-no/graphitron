package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Authoritative ordering specification for a SQL-generating field.
 *
 * <p>Every list/connection field always has a non-null {@link OrderBySpec}. Single-value fields
 * carry {@link None} (the database does not require an ORDER BY for scalar lookups).
 *
 * <p>Three variants:
 * <ul>
 *   <li>{@link Fixed} — a statically resolved ORDER BY derived from {@code @defaultOrder} or the
 *       table's primary key. The column list and direction are known at build time.</li>
 *   <li>{@link Argument} — a dynamic ordering driven by an {@code @orderBy} GraphQL argument.
 *       Contains metadata about the argument ({@code name}, {@code typeName}, {@code sortFieldName},
 *       {@code directionFieldName}), the statically resolved named-order mappings
 *       ({@link NamedOrder}), and a {@link Fixed} {@code base} used as the tiebreaker / fallback
 *       when no {@code @orderBy} argument is supplied at runtime (may be {@code null} when the
 *       table has no primary key and no {@code @defaultOrder} is present).</li>
 *   <li>{@link None} — no ordering is applicable or resolvable (table has no primary key and no
 *       {@code @defaultOrder} directive). The generator will use a jOOQ no-field ordering
 *       placeholder and should emit a warning at generation time.</li>
 * </ul>
 *
 * <p>{@link ColumnOrderEntry} and {@link NamedOrder} are value types used within {@link Fixed}
 * and {@link Argument} respectively. They were previously nested inside
 * {@link FieldWrapper} (as {@code ColumnOrder}, {@code ColumnOrderEntry}, and {@code NamedOrder})
 * and have been relocated here because ordering is no longer a property of the cardinality wrapper.
 */
public sealed interface OrderBySpec
        permits OrderBySpec.Fixed, OrderBySpec.Argument, OrderBySpec.None {

    /**
     * Per-entry sort direction. Decoupled from the SDL {@code SortDirection} enum on purpose:
     * this is the resolved truth the emitter consumes, not the directive-argument value the
     * resolver reads. This was lifted from a whole-spec {@code String} on {@link Fixed} down
     * onto each {@link ColumnOrderEntry}, so a single fixed spec can carry heterogeneous
     * directions (e.g. {@code year DESC, key ASC}).
     */
    enum SortDirection {
        ASC, DESC;

        /** jOOQ sort-direction method name: {@code "asc"} or {@code "desc"}. */
        public String jooqMethodName() { return this == ASC ? "asc" : "desc"; }

        /** Sibling direction; the runtime-flip helper for backward pagination operates on
         *  jOOQ {@code SortField}s, but a flipped-direction algebra lives here for callers
         *  that need it at build time. */
        public SortDirection flipped() { return this == ASC ? DESC : ASC; }
    }

    /**
     * A single entry in a column-order list.
     *
     * <p>{@code column} is the resolved jOOQ {@link ColumnRef}.
     * {@code collation} is the optional {@code COLLATE} clause (e.g. {@code "C"}), or {@code null}
     * when no collation is specified.
     * {@code direction} is the resolved per-entry sort direction. Set by the resolver from the
     * per-field {@code FieldSort.direction:} value if present, otherwise inherited from the
     * directive-level {@code direction:} argument (or {@code ASC} when neither is supplied).
     */
    record ColumnOrderEntry(ColumnRef column, String collation, SortDirection direction) {}

    /**
     * Maps an {@code @orderBy} enum value name to the SQL ORDER BY it represents.
     *
     * <p>{@code name} is the GraphQL enum value name (e.g. {@code "TITLE"}).
     * {@code order} is the resolved column ordering for that enum value.
     */
    record NamedOrder(String name, Fixed order) {}

    /**
     * A statically resolved ORDER BY clause.
     *
     * <p>Used directly as the ordering for fields without a dynamic {@code @orderBy} argument,
     * and as the tiebreaker / fallback inside {@link Argument}.
     *
     * <p>{@code uniformAsc} is {@code true} iff every entry carries {@link SortDirection#ASC}.
     * Computed once at resolution time; consumed by the {@code @orderBy} helper emitter to
     * decide whether the runtime direction arg flips the whole spec (uniform-ASC case) or is
     * ignored because the spec is direction-locked (any non-ASC entry). Harmless when this
     * {@code Fixed} is consumed outside the {@code @orderBy} helper path
     * ({@code @defaultOrder} standalone, PK fallback).
     */
    record Fixed(
        List<ColumnOrderEntry> columns,
        boolean uniformAsc
    ) implements OrderBySpec {}

    /**
     * A dynamic ordering driven by a GraphQL {@code @orderBy} argument.
     *
     * <p>{@code name} is the GraphQL argument name.
     * {@code typeName} is the GraphQL input type name (must be a valid {@code @orderBy} input).
     * {@code sortFieldName} is the input-type field that selects the sort column.
     * {@code directionFieldName} is the input-type field that selects ASC or DESC.
     * {@code namedOrders} maps each enum value to its column-order expression (populated at
     * build time from the enum type's {@code @order} directives).
     * {@code base} is the fallback ordering used when no argument is provided at runtime;
     * {@link None} when the table has no primary key and no {@code @defaultOrder}.
     */
    record Argument(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        String sortFieldName,
        String directionFieldName,
        List<NamedOrder> namedOrders,
        OrderBySpec base
    ) implements OrderBySpec {}

    /**
     * No ordering is applicable.
     *
     * <p>Occurs when the return type is a single value, or when the table has no primary key and
     * no {@code @defaultOrder} directive is present. The generator uses a jOOQ no-field ordering
     * placeholder and should log a warning at generation time.
     */
    record None() implements OrderBySpec {}
}
