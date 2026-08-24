package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.MethodRef;

import java.util.List;

/**
 * One WHERE conjunct of a condition row, split on the single structural axis of who owns the
 * body: a {@link Generated} predicate's terms are ours to render directly in the glue body, an
 * {@link Authored} predicate is an opaque call into developer code with no terms of ours at all.
 * That one axis manifests three ways downstream (the renderer renders terms versus a call, the
 * edge view records an external callee only for authored rows, and override suppression reaps
 * only generated rows), which is what an arm split has to show; presence-gating is per-term data
 * on the generated arm, not an arm.
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
     */
    record Authored(MethodRef method, List<ArgBinding> bindings, ReachPath reach) implements Predicate {
        public Authored {
            if (method == null) {
                throw new IllegalArgumentException("an authored predicate names the developer method it calls");
            }
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            reach = reach == null ? ReachPath.none() : reach;
        }
    }
}
