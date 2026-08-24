package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;

import java.util.ArrayList;
import java.util.List;

/**
 * The join path a condition row reaches through: the hops of a classified {@code @reference}
 * filter path, narrowed once at construction. Empty means the row's predicate binds its own
 * table; non-empty means the predicate wraps in a correlated {@code EXISTS} over these hops,
 * with the comparison or the developer call against the terminal hop's alias.
 *
 * <p>This is the whole filter rail's single narrowing site. The producer hands it the classified
 * {@code List<JoinStep>} and reads {@link #hops()} back hop-typed, so no renderer re-derives
 * "this is a hop" per site. What it does <em>not</em> narrow is the hop's {@link On} arm: the
 * renderer dispatches that per hop, the way the projection rail already does, because a
 * developer-supplied predicate hop correlates through its two-argument call exactly as a
 * foreign-key hop correlates through its column pairs.
 *
 * <p>A lateral routine hop is rejected here, at production, which is where the retired
 * {@code FkHop.narrow} used to throw. {@code @reference} path parsing never mints one, so this is
 * the backstop for the day that changes rather than a reachable author-facing failure.
 *
 * <p>Renderers mint one aliased table local per hop, per reach <em>occurrence</em>: two
 * structurally identical reaches on different terms get their own locals, so the alias map is
 * keyed on this record's identity rather than its value. That per-occurrence grain is stated here
 * because it is a property of what a reach is, not of any one map that holds them.
 */
public record ReachPath(List<JoinStep.Hop> hops) {

    public ReachPath {
        hops = hops == null ? List.of() : List.copyOf(hops);
        for (var hop : hops) {
            if (hop == null) {
                throw new IllegalArgumentException("a reach path carries no null hop");
            }
            if (hop.on() instanceof On.Lateral) {
                throw new IllegalStateException(
                    "a lateral routine hop cannot appear in a condition row's reach path (" + hop
                    + "); @reference path parsing never mints one");
            }
        }
    }

    /** The empty reach: the row's predicate binds its own table. */
    public static ReachPath none() {
        return new ReachPath(List.of());
    }

    /**
     * Narrows a classified path to its hops, the one produce-time narrowing the filter rail
     * performs. {@code context} names the carrier in the failure message; a non-hop step means the
     * classifier admitted a path shape the reach cannot carry, which is a production defect rather
     * than an author error.
     */
    public static ReachPath narrow(List<JoinStep> path, String context) {
        var hops = new ArrayList<JoinStep.Hop>(path.size());
        for (var step : path) {
            hops.add(switch (step) {
                case JoinStep.Hop hop -> hop;
            });
        }
        try {
            return new ReachPath(hops);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                "condition reach path for " + context + " is not renderable: " + e.getMessage(), e);
        }
    }

    public boolean isEmpty() {
        return hops.isEmpty();
    }

    public int size() {
        return hops.size();
    }

    /** The hop at {@code index}; the terminal hop is the one the inner predicate binds against. */
    public JoinStep.Hop hop(int index) {
        return hops.get(index);
    }
}
