package no.sikt.graphitron.rewrite.compile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable adjacency-map implementation of {@link CompileDependencyGraph}. Built through the
 * mutable {@link Accumulator} (the builder's working set), then frozen: forward and reverse
 * adjacency are materialised once so both {@link #directReferences} and {@link #directDependents}
 * are O(1) reads with no per-query traversal.
 */
final class MapCompileDependencyGraph implements CompileDependencyGraph {

    private final Set<String> nodes;
    private final Map<String, Set<String>> forward;
    private final Map<String, Set<String>> reverse;

    private MapCompileDependencyGraph(Set<String> nodes,
                                      Map<String, Set<String>> forward,
                                      Map<String, Set<String>> reverse) {
        this.nodes = nodes;
        this.forward = forward;
        this.reverse = reverse;
    }

    @Override
    public Set<String> nodes() {
        return nodes;
    }

    @Override
    public Set<String> directReferences(String node) {
        return forward.getOrDefault(node, Set.of());
    }

    @Override
    public Set<String> directDependents(String node) {
        return reverse.getOrDefault(node, Set.of());
    }

    /**
     * Mutable working set the builder populates. Nodes and edges may be added in any order; an edge
     * whose endpoints are not (yet) registered as nodes registers them, so the builder can declare a
     * reference before or after declaring the referenced unit. Self-edges are dropped: a unit never
     * depends on itself for recompilation purposes.
     */
    static final class Accumulator {
        private final Set<String> nodes = new LinkedHashSet<>();
        private final Map<String, Set<String>> forward = new LinkedHashMap<>();

        void addNode(String fqcn) {
            nodes.add(fqcn);
        }

        /** Live view of the nodes registered so far (edges add their endpoints as nodes too). */
        Set<String> nodesSnapshot() {
            return Set.copyOf(nodes);
        }

        /** Records that {@code from} references a type declared in {@code to}. */
        void addEdge(String from, String to) {
            if (from.equals(to)) {
                return;
            }
            nodes.add(from);
            nodes.add(to);
            forward.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
        }

        CompileDependencyGraph build() {
            var frozenForward = new LinkedHashMap<String, Set<String>>();
            var reverse = new LinkedHashMap<String, Set<String>>();
            for (var e : forward.entrySet()) {
                frozenForward.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
                for (String to : e.getValue()) {
                    reverse.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(e.getKey());
                }
            }
            var frozenReverse = new LinkedHashMap<String, Set<String>>();
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
