package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.FacetSpec;

import java.util.List;
import java.util.Objects;

/**
 * The facet plan a faceted {@code @asConnection} carrier binds: the base condition fragment (the
 * coordinate's filter minus every facet's own predicate) and one entry per facet pairing its
 * decode spec with its own-predicate fragment. The fragment refs are {@link GlueCall}s into the
 * condition relation's masked glue variants (the condition family produces them; this plan
 * consumes refs and mints nothing of its own), sharing the row-grained env-appending fork with
 * the coordinate's main glue. The decode data ({@code columnName}, {@code valueNullable}, the
 * facet's input field name) is the model's {@link FacetSpec}, borrowed outright rather than
 * copied field by field, per the borrow-don't-mint rule.
 */
public record FacetPlan(GlueCall base, List<Entry> facets) {

    /** One facet: its model spec and the glue fragment computing its own predicate alone. */
    public record Entry(FacetSpec spec, GlueCall condition) {
        public Entry {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(condition, "condition");
        }
    }

    public FacetPlan {
        Objects.requireNonNull(base, "base");
        facets = List.copyOf(facets);
        if (facets.isEmpty()) {
            throw new IllegalArgumentException(
                "a facet plan exists exactly when the coordinate carries facets; model the"
                + " non-faceted carrier as an absent plan, not an empty one");
        }
    }
}
