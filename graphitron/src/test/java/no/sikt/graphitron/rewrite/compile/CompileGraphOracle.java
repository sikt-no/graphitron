package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.command.UnitRef;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The incremental harness's two reference legs over a {@link CompileDependencyGraph}, as functions
 * that return what they find rather than assert, so a test can feed them a deliberately broken
 * graph. {@code references} is the oracle's account of what each unit actually references, keyed
 * by FQCN ({@link JavacReferenceIndex}).
 */
final class CompileGraphOracle {

    private CompileGraphOracle() {
    }

    /**
     * Emit to graph: the references the graph lacks, per source unit. A gap is a dependency an
     * incremental recompile would silently skip. Units in {@code frozenSources} are exempt as
     * sources: their content is schema-independent, so they never enter a schema-driven delta and
     * their outgoing edges never drive a recompile. They stay checked as targets.
     */
    static Map<String, Set<String>> gaps(CompileDependencyGraph graph, Map<String, Set<String>> references,
                                         Set<String> frozenSources) {
        var nodes = nodesByFqcn(graph);
        var gaps = new TreeMap<String, Set<String>>();
        references.forEach((unit, referenced) -> {
            if (frozenSources.contains(unit)) {
                return;
            }
            var missing = new TreeSet<>(referenced);
            missing.removeAll(directReferences(graph, nodes.get(unit)));
            if (!missing.isEmpty()) {
                gaps.put(unit, missing);
            }
        });
        return gaps;
    }

    /**
     * Bounded gap: the graph's edges the oracle does not find and {@code declaredSuperset} does not
     * cover, per source unit. Silent over-collection in the graph's precise edges shows up here.
     */
    static Map<String, Set<String>> undeclared(CompileDependencyGraph graph, CompileDependencyGraph declaredSuperset,
                                               Map<String, Set<String>> references) {
        var undeclared = new TreeMap<String, Set<String>>();
        for (UnitRef node : graph.nodes()) {
            var surplus = new TreeSet<>(directReferences(graph, node));
            surplus.removeAll(references.getOrDefault(node.fqcn(), Set.of()));
            surplus.removeAll(directReferences(declaredSuperset, node));
            if (!surplus.isEmpty()) {
                undeclared.put(node.fqcn(), surplus);
            }
        }
        return undeclared;
    }

    private static Map<String, UnitRef> nodesByFqcn(CompileDependencyGraph graph) {
        var out = new TreeMap<String, UnitRef>();
        graph.nodes().forEach(node -> out.put(node.fqcn(), node));
        return out;
    }

    private static Set<String> directReferences(CompileDependencyGraph graph, UnitRef node) {
        var out = new TreeSet<String>();
        if (node != null) {
            graph.directReferences(node).forEach(ref -> out.add(ref.fqcn()));
        }
        return out;
    }
}
