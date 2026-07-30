package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.command.UnitRef;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable adjacency-map implementation of {@link CompileDependencyGraph}. Built through the
 * mutable {@link Accumulator} (the projection's working set), then frozen: forward and reverse
 * adjacency are materialised once so both {@link #directReferences} and {@link #directDependents}
 * are O(1) reads with no per-query traversal.
 */
final class MapCompileDependencyGraph implements CompileDependencyGraph {

    private final Set<UnitRef> nodes;
    private final Map<UnitRef, Set<UnitRef>> forward;
    private final Map<UnitRef, Set<UnitRef>> reverse;

    private MapCompileDependencyGraph(Set<UnitRef> nodes,
                                      Map<UnitRef, Set<UnitRef>> forward,
                                      Map<UnitRef, Set<UnitRef>> reverse) {
        this.nodes = nodes;
        this.forward = forward;
        this.reverse = reverse;
    }

    @Override
    public Set<UnitRef> nodes() {
        return nodes;
    }

    @Override
    public Set<UnitRef> directReferences(UnitRef node) {
        return forward.getOrDefault(node, Set.of());
    }

    @Override
    public Set<UnitRef> directDependents(UnitRef node) {
        return reverse.getOrDefault(node, Set.of());
    }

    /**
     * Mutable working set the projection populates. Nodes and edges may be added in any order; an
     * edge whose endpoints are not (yet) registered as nodes registers them, so a reference may be
     * declared before or after the referenced unit. Self-edges are dropped: a unit never depends
     * on itself for recompilation purposes.
     */
    static final class Accumulator {
        private final Set<UnitRef> nodes = new LinkedHashSet<>();
        private final Map<UnitRef, Set<UnitRef>> forward = new LinkedHashMap<>();

        void addNode(UnitRef unit) {
            nodes.add(unit);
        }

        /** Records that {@code from} references a type declared in {@code to}. */
        void addEdge(UnitRef from, UnitRef to) {
            if (from.equals(to)) {
                return;
            }
            nodes.add(from);
            nodes.add(to);
            forward.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
        }

        CompileDependencyGraph build() {
            var frozenForward = new LinkedHashMap<UnitRef, Set<UnitRef>>();
            var reverse = new LinkedHashMap<UnitRef, Set<UnitRef>>();
            for (var e : forward.entrySet()) {
                frozenForward.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
                for (UnitRef to : e.getValue()) {
                    reverse.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(e.getKey());
                }
            }
            var frozenReverse = new LinkedHashMap<UnitRef, Set<UnitRef>>();
            for (var e : reverse.entrySet()) {
                frozenReverse.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
            }
            return new MapCompileDependencyGraph(
                Collections.unmodifiableSet(new LinkedHashSet<>(nodes)),
                Collections.unmodifiableMap(frozenForward),
                Collections.unmodifiableMap(frozenReverse));
        }
    }
}
