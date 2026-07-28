package no.sikt.graphitron.command;

import java.util.List;

/**
 * One masked glue variant of a condition row, for faceted {@code @asConnection} carriers: the
 * base fragment (the row's predicates minus every facet's own generated term) and one per-facet
 * fragment (that facet's own generated term alone). The producer computes each fragment's
 * predicate list outright, so no mask vocabulary exists: a fragment is just another
 * (method, predicates) pair rendered by the same body convention. A masked generated term is
 * statically omitted, which renders the same SQL as the runtime null-guard dropping the conjunct
 * because facet bindings are guaranteed nullable (pinned at classify time by
 * {@code GraphitronSchemaBuilder}'s facet-misuse rejection, which rejects non-null facet fields).
 *
 * <p>{@link #predicates} may be empty (a base fragment whose row is entirely facet-owned renders
 * the neutral condition); {@link #lifts} are computed over this fragment's own retained bindings.
 */
public record FacetFragment(UnitMethodRef method, List<Predicate> predicates, List<OuterLift> lifts) {

    public FacetFragment {
        if (method == null) {
            throw new IllegalArgumentException("a facet fragment carries its minted method reference");
        }
        predicates = predicates == null ? List.of() : List.copyOf(predicates);
        lifts = lifts == null ? List.of() : List.copyOf(lifts);
    }
}
