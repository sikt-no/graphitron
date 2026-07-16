package no.sikt.graphitron.rewrite.compile;

import java.util.Set;

/**
 * The file-level compile-dependency graph over graphitron's generated compilation
 * units. Nodes are generated {@code .java} units, identified by their fully-qualified class name
 * (FQCN); edges are "references a type declared in", coarsened to file granularity (a method-call
 * seam between two generated methods collapses into a reference between the two files those methods
 * are emitted into).
 *
 * <p>This is <em>the sourcing seam</em>: the single interface between "where the edges come from"
 * (today {@link CompileDependencyGraphBuilder}'s coarsening projection of the classified model,
 * later a view over the model's method graph) and "the compiler that consumes them" (the incremental
 * engine, slice 4). The engine reads only this interface, so re-sourcing the edges never touches the
 * consumer.
 *
 * <p><b>Completeness contract.</b> The graph must be a <em>superset</em> of javac's true dependency
 * edges: a missing edge would let an incremental compile silently skip a unit that needed
 * recompiling (a wrong-output bug), whereas an extra edge only ever costs a redundant recompile. The
 * builder therefore over-approximates where a precise edge is not cheaply model-derivable
 * ({@link UtilSingleton.FrozenScaffold}), and stays precise only where over-approximation would harm
 * pruning ({@link UtilSingleton.PerTypeGrowing}). {@link TypeSpecReferenceWalk} is the completeness
 * oracle that falsifies under-approximation in tests.
 */
public interface CompileDependencyGraph {

    /** Every generated compilation unit in the graph, as fully-qualified class names. */
    Set<String> nodes();

    /**
     * The generated units {@code node} references directly (forward edges). Never includes references
     * into consumer code, jOOQ tables, or dependency jars: those are already compiled and on the
     * resolved classpath, so they are not nodes. Returns an empty set for an unknown node.
     */
    Set<String> directReferences(String node);

    /**
     * The generated units that reference {@code node} directly (reverse edges) — the one-hop
     * dependents. The incremental engine walks these transitively to build the recompile set
     * (slice 3). Returns an empty set for an unknown node.
     */
    Set<String> directDependents(String node);
}
