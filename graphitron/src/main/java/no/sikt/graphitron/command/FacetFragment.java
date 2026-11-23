package no.sikt.graphitron.command;

import java.util.Optional;

/**
 * One masked glue variant of a condition row, for faceted {@code @asConnection} carriers: the
 * {@link Base} fragment (the row's predicates minus every facet's own generated term) and one
 * {@link Own} fragment per facet (that facet's own generated term alone). A masked generated term
 * is statically omitted, which renders the same SQL as the runtime null-guard dropping the
 * conjunct because facet bindings are guaranteed nullable (pinned at classify time by
 * {@code GraphitronSchemaBuilder}'s facet-misuse rejection, which rejects non-null facet fields).
 */
public sealed interface FacetFragment {

    /** The minted glue method this fragment renders as. */
    UnitMethodRef method();

    /** The row's predicates minus every facet's own generated term, authored predicates unmasked. */
    record Base(UnitMethodRef method) implements FacetFragment {

        public Base {
            requireMethod(method);
        }
    }

    /**
     * One facet's own fragment, keyed by the facet's input field name: its own generated term
     * alone, or no term when the facet's implicit predicate was suppressed (an enclosing
     * {@code @condition(override: true)}). At most one term is the shape, not a producer
     * convention: a facet field binds one filter value, and the planner reads that term's reach
     * and column as the facet's grouping.
     */
    record Own(String inputFieldName, UnitMethodRef method, Optional<ColumnTerm> term)
            implements FacetFragment {

        public Own {
            if (inputFieldName == null || inputFieldName.isEmpty()) {
                throw new IllegalArgumentException("a facet's own fragment is keyed by its input field name");
            }
            requireMethod(method);
            term = term == null ? Optional.empty() : term;
        }
    }

    private static void requireMethod(UnitMethodRef method) {
        if (method == null) {
            throw new IllegalArgumentException("a facet fragment carries its minted method reference");
        }
    }
}
