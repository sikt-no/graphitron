package no.sikt.graphitron.command;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.FacetSpec;

import java.util.List;
import java.util.Objects;

/**
 * The facet plan a faceted {@code @asConnection} carrier binds: the base condition fragment (the
 * coordinate's filter minus every facet's own predicate) and one entry per facet pairing its
 * decode spec with its own-predicate fragment and its grouping. The fragment refs are
 * {@link GlueCall}s into the condition relation's masked glue variants (the condition family
 * produces them; this plan consumes refs and mints nothing of its own), sharing the row-grained
 * env-appending fork with the coordinate's main glue. The decode data ({@code valueNullable}, the
 * facet's input field name) is the model's {@link FacetSpec}, borrowed outright rather than
 * copied field by field, per the borrow-don't-mint rule.
 *
 * <p>Which column on which table a facet's counts group on is the plan's own fact, the
 * {@link Grouping}, borrowed off the facet's own filter term so the facet join and the filter's
 * {@code EXISTS} narrow one path. {@link #distinctKey} is the carrier's primary key, what a
 * {@link Grouping.Reached} facet counts distinct over: a path that fans out multiplies carrier rows
 * in the join, and the distinct count is the number of rows selecting that value alone returns.
 */
public record FacetPlan(GlueCall base, List<Entry> facets, List<ColumnRef> distinctKey) {

    /**
     * One facet: its model spec, the glue fragment computing its own predicate alone, and the
     * column its counts group on.
     */
    public record Entry(FacetSpec spec, GlueCall condition, Grouping grouping) {
        public Entry {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(grouping, "grouping");
        }
    }

    /** Where a facet's group column sits relative to the carrier's FROM. */
    public sealed interface Grouping {

        /** The column the counts group on. */
        ColumnRef column();

        /** A column on the carrier's own table: the arm groups and counts over the carrier's FROM. */
        record CarrierColumn(ColumnRef column) implements Grouping {
            public CarrierColumn {
                Objects.requireNonNull(column, "column");
            }
        }

        /**
         * A column on the table {@code reach} ends at: the arm inner-joins the carrier's table
         * along the reach and counts distinct carrier keys.
         */
        record Reached(ReachPath reach, ColumnRef column) implements Grouping {
            public Reached {
                Objects.requireNonNull(reach, "reach");
                Objects.requireNonNull(column, "column");
                if (reach.isEmpty()) {
                    throw new IllegalArgumentException(
                        "a reached facet grouping crosses at least one hop; a column on the carrier's"
                        + " own table is a CarrierColumn grouping");
                }
            }
        }
    }

    public FacetPlan {
        Objects.requireNonNull(base, "base");
        facets = List.copyOf(facets);
        distinctKey = distinctKey == null ? List.of() : List.copyOf(distinctKey);
        if (facets.isEmpty()) {
            throw new IllegalArgumentException(
                "a facet plan exists exactly when the coordinate carries facets; model the"
                + " non-faceted carrier as an absent plan, not an empty one");
        }
        if (distinctKey.isEmpty()
                && facets.stream().anyMatch(f -> f.grouping() instanceof Grouping.Reached)) {
            throw new IllegalArgumentException(
                "a facet plan with a reached grouping counts distinct carrier keys, so it carries"
                + " the carrier's primary key; the validator rejects a @reference facet on a carrier"
                + " without one");
        }
    }

    /** True when any facet groups across a join. */
    public boolean hasReachedGrouping() {
        return facets.stream().anyMatch(f -> f.grouping() instanceof Grouping.Reached);
    }
}
