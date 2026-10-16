package no.sikt.graphitron.command;

import java.util.List;
import java.util.Optional;

/**
 * One masked glue variant of a condition row, for faceted {@code @asConnection} carriers: the
 * {@link Base} fragment (the row's predicates minus every facet's own generated term) and one
 * {@link Own} fragment per facet (that facet's own generated term alone). The producer computes
 * each fragment's predicate list outright, so no mask vocabulary exists: a fragment is just another
 * (method, predicates) pair rendered by the same body convention. A masked generated term is
 * statically omitted, which renders the same SQL as the runtime null-guard dropping the conjunct
 * because facet bindings are guaranteed nullable (pinned at classify time by
 * {@code GraphitronSchemaBuilder}'s facet-misuse rejection, which rejects non-null facet fields).
 *
 * <p>{@link #predicates} may be empty (a base fragment whose row is entirely facet-owned, or a
 * facet whose own term was suppressed, renders the neutral condition); {@link #lifts} are computed
 * over this fragment's own retained bindings.
 */
public sealed interface FacetFragment {

    /** The minted glue method this fragment renders as. */
    UnitMethodRef method();

    /** The predicates this fragment's method composes. */
    List<Predicate> predicates();

    /** The outer lifts over this fragment's own retained bindings. */
    List<OuterLift> lifts();

    /** The row's predicates minus every facet's own generated term, authored predicates unmasked. */
    record Base(UnitMethodRef method, List<Predicate> predicates, List<OuterLift> lifts)
            implements FacetFragment {

        public Base {
            requireMethod(method);
            predicates = predicates == null ? List.of() : List.copyOf(predicates);
            lifts = lifts == null ? List.of() : List.copyOf(lifts);
        }
    }

    /**
     * One facet's own fragment, keyed by the facet's input field name: its own generated term
     * alone, or no term when the facet's implicit predicate was suppressed (an enclosing
     * {@code @condition(override: true)}). At most one term is the shape, not a producer
     * convention: a facet field binds one filter value, and the planner reads that term's reach
     * and column as the facet's grouping.
     */
    record Own(String inputFieldName, UnitMethodRef method, Optional<ColumnTerm> term,
            List<OuterLift> lifts) implements FacetFragment {

        public Own {
            if (inputFieldName == null || inputFieldName.isEmpty()) {
                throw new IllegalArgumentException("a facet's own fragment is keyed by its input field name");
            }
            requireMethod(method);
            term = term == null ? Optional.empty() : term;
            lifts = lifts == null ? List.of() : List.copyOf(lifts);
        }

        @Override
        public List<Predicate> predicates() {
            return term.<List<Predicate>>map(t -> List.of(new Predicate.Generated(List.of(t)))).orElse(List.of());
        }
    }

    private static void requireMethod(UnitMethodRef method) {
        if (method == null) {
            throw new IllegalArgumentException("a facet fragment carries its minted method reference");
        }
    }
}
