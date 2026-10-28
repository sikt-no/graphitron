package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.Objects;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * Authoritative ordering specification for a SQL-generating field.
 *
 * <p>Every list/connection field carries a non-null {@link OrderBySpec}; single-value fields
 * carry {@link None} (the database does not require an ORDER BY for scalar lookups).
 * {@link OrderEntry} and {@link NamedOrder} are value types used within {@link Fixed}
 * and {@link Argument} respectively.
 */
public sealed interface OrderBySpec
        permits OrderBySpec.Fixed, OrderBySpec.Argument, OrderBySpec.None {

    /**
     * Per-entry sort direction. Decoupled from the SDL {@code SortDirection} enum on purpose:
     * this is the resolved truth the emitter consumes, not the directive-argument value the
     * resolver reads. Direction lives on each {@link OrderEntry}, so a single fixed spec
     * can carry heterogeneous directions (e.g. {@code year DESC, key ASC}).
     */
    enum SortDirection {
        ASC, DESC;

        /** jOOQ sort-direction method name: {@code "asc"} or {@code "desc"}. */
        public String jooqMethodName() { return this == ASC ? "asc" : "desc"; }

        /** Sibling direction, for callers that flip at build time (the runtime
         *  backward-pagination flip operates on jOOQ {@code SortField}s instead). */
        public SortDirection flipped() { return this == ASC ? DESC : ASC; }
    }

    /**
     * One entry of a {@link Fixed} order: an own-table column ({@link ColumnOrderEntry}) or a sort
     * key the field's table does not hold ({@link SortKeyEntry}). Every entry carries the three
     * facts every order rendering reads: the optional {@code COLLATE} name, the resolved
     * direction and whether the sort value admits {@code NULL}.
     */
    sealed interface OrderEntry permits ColumnOrderEntry, SortKeyEntry {
        String collation();
        SortDirection direction();
        boolean nullable();
    }

    /**
     * An own-table column entry.
     *
     * <p>{@code column} is the resolved jOOQ {@link ColumnRef}.
     * {@code collation} is the optional {@code COLLATE} clause (e.g. {@code "C"}), or {@code null}
     * when no collation is specified.
     * {@code direction} is the resolved per-entry sort direction. Set by the resolver from the
     * per-field {@code FieldSort.direction:} value if present, otherwise inherited from the
     * directive-level {@code direction:} argument (or {@code ASC} when neither is supplied).
     * {@code nullable} is whether the column admits {@code NULL}, as the catalog reports it; an
     * emitter renders such an entry {@code NULLS LAST} in either direction, which is also what makes
     * jOOQ's keyset seek step through the {@code NULL} rows. The row-key columns
     * {@code OrderByResolver.complete} appends carry {@code false}: they identify the row.
     */
    record ColumnOrderEntry(ColumnRef column, String collation, SortDirection direction, boolean nullable)
            implements OrderEntry {}

    /**
     * An entry whose sort value is not a column of the field's table: a column reached through
     * to-one hops ({@link SortKey.Path}), or an expression a consumer method derives
     * ({@link SortKey.Method}). Both render as one call expression over the alias the site orders
     * on, so no site's {@code FROM} changes.
     *
     * <p>{@code ordinal} numbers the field's sort-key entries across its fixed order, its default
     * order and every named order of its {@code @orderBy} enum, so it is unique per field. The
     * emitted method a path key renders through and the reserved alias the key is projected under
     * for the cursor are both minted from it.
     *
     * <p>The key is always {@link #nullable()}: a path key is {@code NULL} for a parent with no
     * related row, and a method key's nullability is not visible to the build.
     */
    record SortKeyEntry(SortKey key, String collation, SortDirection direction, int ordinal)
            implements OrderEntry {
        public SortKeyEntry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(direction, "direction");
            if (ordinal < 0) {
                throw new IllegalArgumentException("SortKeyEntry.ordinal must be non-negative; got " + ordinal);
            }
        }

        @Override public boolean nullable() { return true; }
    }

    /** What a {@link SortKeyEntry} sorts by. */
    sealed interface SortKey {

        /**
         * {@code column} on the table the to-one {@code hops} land on, walked from the field's
         * table. Every hop is an {@link On.ColumnPairs} hop with no filter, so the first hop
         * correlates on its FK slots and the subquery returns at most one row.
         */
        record Path(List<JoinStep.Hop> hops, ColumnRef column) implements SortKey {
            public Path {
                hops = List.copyOf(hops);
                if (hops.isEmpty()) {
                    throw new IllegalArgumentException("SortKey.Path requires at least one hop");
                }
                Objects.requireNonNull(column, "column");
                for (var hop : hops) {
                    if (!(hop.on() instanceof On.ColumnPairs) || hop.filter() != null) {
                        throw new IllegalArgumentException(
                            "SortKey.Path hops are unfiltered key joins; the resolver rejects every other hop");
                    }
                }
            }

            /** The field's table, where the walk starts. */
            public TableRef startTable() { return hops.getFirst().originTable(); }
        }

        /**
         * A consumer static method taking the field's table and returning the sort key as a
         * {@code Field<?>}, the {@code @externalField} calling convention.
         */
        record Method(MethodRef.StaticOnly method) implements SortKey {
            public Method { Objects.requireNonNull(method, "method"); }
        }
    }

    /**
     * Maps an {@code @orderBy} enum value name to the SQL ORDER BY it represents.
     *
     * <p>{@code name} is the GraphQL enum value name (e.g. {@code "TITLE"}).
     * {@code order} is the resolved column ordering for that enum value.
     */
    record NamedOrder(String name, Fixed order) {}

    /**
     * A statically resolved ORDER BY clause: the ordering for fields without a dynamic
     * {@code @orderBy} argument, and the tiebreaker / fallback inside {@link Argument}.
     *
     * <p>{@code uniformAsc} is {@code true} iff every entry carries {@link SortDirection#ASC}.
     * Computed once at resolution time; the {@code @orderBy} helper emitter
     * ({@link no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator}) uses it to decide
     * whether the runtime direction argument flips the whole spec (uniform-ASC) or is ignored
     * because the spec is direction-locked (any non-ASC entry).
     *
     * <p>On a single-table connection the resolver hands out the order already completed to
     * identify a row: when the authored own-table columns cover no identifying key, the table's row
     * key follows them in the direction of the last authored entry, so {@code uniformAsc} is what
     * it was before completion. A {@link SortKeyEntry} never counts toward that coverage, since a
     * related row or a derived value can be shared by many parents. Consumers see one list and need
     * not know which entries were appended.
     */
    record Fixed(
        List<OrderEntry> entries,
        boolean uniformAsc
    ) implements OrderBySpec {
        public Fixed { entries = List.copyOf(entries); }

        /** The sort-key entries, in order. */
        public List<SortKeyEntry> sortKeys() {
            return entries.stream()
                .filter(e -> e instanceof SortKeyEntry)
                .map(e -> (SortKeyEntry) e)
                .toList();
        }
    }

    /**
     * A dynamic ordering driven by a GraphQL {@code @orderBy} argument.
     *
     * <p>{@code name} is the GraphQL argument name.
     * {@code typeName} is the GraphQL input type name (must be a valid {@code @orderBy} input).
     * {@code composition} says whether the argument takes one sort value or a client-composed
     * sequence of them, and carries each enum value's column-order expression (populated at
     * build time from the enum type's {@code @order} directives).
     * {@code sortFieldName} is the input-type field that selects the sort column.
     * {@code directionFieldName} is the input-type field that selects ASC or DESC.
     * {@code base} is the fallback ordering used when no argument is provided at runtime;
     * {@link None} when the table has no primary key and no {@code @defaultOrder}. On a
     * single-table connection it is completed like any {@link Fixed}.
     */
    record Argument(
        String name,
        String typeName,
        boolean nonNull,
        Composition composition,
        String sortFieldName,
        String directionFieldName,
        OrderBySpec base
    ) implements OrderBySpec {

        /** The named orders, whichever composition carries them. */
        public List<NamedOrder> namedOrders() {
            return composition.namedOrders();
        }

        /** Whether the argument is list-valued, so the client composes a sequence of values. */
        public boolean list() {
            return composition instanceof Composition.Sequence;
        }
    }

    /**
     * How an {@link Argument}'s values compose into one ORDER BY. The two arms differ in where a
     * connection's row-identifying completion happens, which is why the shape is sealed rather
     * than a flag.
     */
    sealed interface Composition {
        List<NamedOrder> namedOrders();

        /**
         * A single-valued argument: the request picks exactly one named order. On a single-table
         * connection each named order arrives already completed, so the helper renders it as is.
         */
        record Single(List<NamedOrder> namedOrders) implements Composition {
            public Single { namedOrders = List.copyOf(namedOrders); }
        }

        /**
         * A list-valued argument: the request concatenates named orders. Each one is carried as
         * declared, because completing them one by one would repeat the key after every value;
         * {@code completion} says what the helper appends to the composed sequence.
         */
        record Sequence(List<NamedOrder> namedOrders, SequenceCompletion completion) implements Composition {
            public Sequence { namedOrders = List.copyOf(namedOrders); }
        }
    }

    /** What the {@link Composition.Sequence} helper appends after composing the request's values. */
    sealed interface SequenceCompletion {

        /** Nothing: a plain list, a multitable participant, or a table with no row key. */
        record None() implements SequenceCompletion {}

        /**
         * When the composed columns contain no {@code identifyingKeys} entry in full, append the
         * {@code rowKey} columns they lack, in the direction of the last composed sort part.
         */
        record AppendRowKey(List<ColumnRef> rowKey, List<List<ColumnRef>> identifyingKeys)
                implements SequenceCompletion {
            public AppendRowKey {
                rowKey = List.copyOf(rowKey);
                identifyingKeys = identifyingKeys.stream().map(List::copyOf).toList();
            }
        }
    }

    /**
     * No ordering is applicable.
     *
     * <p>Occurs when the return type is a single value, or when the table has no primary key and
     * no {@code @defaultOrder} directive is present. The generator uses a jOOQ no-field ordering
     * placeholder.
     */
    record None() implements OrderBySpec {}
}
