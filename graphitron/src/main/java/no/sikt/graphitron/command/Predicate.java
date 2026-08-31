package no.sikt.graphitron.command;

import java.util.List;

/**
 * One WHERE conjunct of a condition row, split on the single structural axis of who owns the
 * body: a {@link Generated} predicate's terms are ours to render directly in the glue body, an
 * {@link Authored} predicate is an opaque call into developer code with no terms of ours at all.
 * That one axis manifests three ways downstream (the renderer renders terms versus a call, the
 * edge view records an external callee only for authored rows, and override suppression reaps
 * only generated rows), which is what an arm split has to show; presence-gating is data on both
 * arms rather than an arm of its own, at the grain where each arm's wrapper sits: per-term on the
 * generated arm, whose guard wraps one comparison, and per-predicate on the authored arm, whose
 * guard wraps the whole call.
 *
 * <p>Naming heads-up: this shadows the model's {@code On.Predicate}; the two never meet in one
 * expression, and the name is the natural one here.
 */
public sealed interface Predicate {

    /** Graphitron-minted column predicate; its terms render directly in the glue body. */
    record Generated(List<ColumnTerm> terms) implements Predicate {
        public Generated {
            if (terms == null || terms.isEmpty()) {
                throw new IllegalArgumentException(
                    "a generated predicate carries at least one term; a term-less conjunct is no conjunct");
            }
            terms = List.copyOf(terms);
        }
    }

    /**
     * Developer {@code @condition} method: an opaque external call. {@link #reach} empty means
     * the method receives this row's own table; non-empty means the whole call wraps in a
     * correlated {@code EXISTS} over the {@link ReachPath}'s hops and the method receives the
     * terminal hop's alias (the FK-target form). Reach sits per-predicate here because the wrap
     * covers the whole call, unlike the generated arm's per-term grain.
     *
     * <p>The method is named by the two components a call site emits, not by the model's
     * reflected reference: an authored predicate's whole render is
     * {@code Class.method(table, locals...)}, and the signature facts the reflected reference
     * also carries are read at classification time and by nothing downstream of this row.
     *
     * <p>{@link #presence} says when the conjunct is contributed. A non-empty reach always carries
     * a {@link PresenceGuard.FieldPresent}: the {@code EXISTS} is a semi-join graphitron mints
     * around the call, so firing it for a value nobody supplied would silently drop every row with
     * no far-side relation, and the invariant here makes that shape unconstructable rather than a
     * renderer-side assertion.
     */
    record Authored(AuthoredMethodRef method, List<ArgBinding> bindings, ReachPath reach,
            PresenceGuard presence) implements Predicate {
        public Authored {
            if (method == null) {
                throw new IllegalArgumentException("an authored predicate names the developer method it calls");
            }
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            reach = reach == null ? ReachPath.none() : reach;
            presence = presence == null ? PresenceGuard.always() : presence;
            if (!reach.isEmpty() && !(presence instanceof PresenceGuard.FieldPresent)) {
                throw new IllegalArgumentException(
                    "authored predicate calling '" + method.methodName() + "' reaches through "
                    + reach.size() + " hop(s) with no field-presence guard; the correlated EXISTS is a "
                    + "semi-join graphitron mints, and an absent filter value contributes no conjunct");
            }
        }
    }
}
