package no.sikt.graphitron.rewrite.compile;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The recompile-set algorithm as pure functions over the {@link CompileDependencyGraph}
 * and the per-unit ABI hashes ({@link AbiSignature}). Given the writer's delta (the content-changed
 * units) this computes which units a round must recompile:
 *
 * <pre>recompile = delta ∪ { reverse-transitive dependents of the delta units whose ABI changed }</pre>
 *
 * <p>The two clauses the acceptance gate pins live here structurally:
 * <ul>
 *   <li><b>Pruning.</b> A body-only edit leaves a unit's ABI hash unchanged, so it is not in
 *       {@link #abiChanged}, so it seeds no reverse traversal: its dependents are excluded. Only the
 *       edited unit recompiles.</li>
 *   <li><b>Completeness.</b> An ABI edit moves the hash, seeding a full reverse-transitive closure
 *       over {@link CompileDependencyGraph#directDependents}: every unit that (transitively) compiles
 *       against the changed surface is included. The closure does not re-gate at each hop, so it is a
 *       conservative superset, which is what soundness requires.</li>
 * </ul>
 */
public final class RecompileSet {

    private RecompileSet() {}

    /**
     * The subset of {@code delta} whose signature surface moved since the previous round. A unit
     * present in {@code delta} but absent from {@code previousHashes} is a newly emitted unit and
     * counts as ABI-changed (it has no prior surface for a dependent to have compiled against, so any
     * dependent already in the delta, and the unit itself, must be treated as a fresh surface).
     *
     * @param delta          content-changed units this round (the writer's report)
     * @param previousHashes ABI hash per unit as of the previous round ({@link AbiSignature#hash})
     * @param currentHashes  ABI hash per unit as of this round
     */
    public static Set<String> abiChanged(Set<String> delta,
                                         Map<String, String> previousHashes,
                                         Map<String, String> currentHashes) {
        Set<String> changed = new LinkedHashSet<>();
        for (String unit : delta) {
            String previous = previousHashes.get(unit);
            String current = currentHashes.get(unit);
            if (previous == null || !previous.equals(current)) {
                changed.add(unit);
            }
        }
        return changed;
    }

    /**
     * The units to recompile this round: the delta plus the reverse-transitive dependents of every
     * ABI-changed unit. Pure over {@code graph}; {@code abiChanged} is normally a subset of
     * {@code delta} but any extra seed is tolerated (its closure is simply included).
     */
    public static Set<String> compute(CompileDependencyGraph graph,
                                      Set<String> delta,
                                      Set<String> abiChanged) {
        Set<String> recompile = new LinkedHashSet<>(delta);
        Set<String> visited = new LinkedHashSet<>(abiChanged);
        Deque<String> frontier = new ArrayDeque<>(abiChanged);
        while (!frontier.isEmpty()) {
            String unit = frontier.poll();
            for (String dependent : graph.directDependents(unit)) {
                if (visited.add(dependent)) {
                    frontier.add(dependent);
                }
            }
        }
        recompile.addAll(visited);
        return recompile;
    }
}
