package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.command.UnitRef;

import java.util.Set;

/**
 * The file-level compile-dependency graph over graphitron's generated compilation units. Nodes
 * are generated {@code .java} units, identified by their typed {@link UnitRef}; edges are
 * "references a type declared in", coarsened to file granularity (a method-call seam between two
 * generated methods collapses into a reference between the two files those methods are emitted
 * into).
 *
 * <p>This is <em>the sourcing seam</em>: the single interface between "where the edges come
 * from" ({@link PlanCompileGraph}'s projection over the {@link no.sikt.graphitron.plan.EmitPlan}'s
 * relations) and "the compiler that consumes them" (the incremental engine). The engine reads
 * only this interface, so re-sourcing the edges never touches the consumer. The engine keys ABI
 * hashes and javac units by FQCN string; that stringification happens in exactly one adapter, at
 * {@link RecompileSet#compute}, so the graph itself stays typed.
 *
 * <p><b>Completeness contract.</b> The graph must be a <em>superset</em> of javac's true
 * dependency edges: a missing edge would let an incremental compile silently skip a unit that
 * needed recompiling (a wrong-output bug), whereas an extra edge only ever costs a redundant
 * recompile. The projection over-approximates only through its <em>declared</em> superset edges
 * (the frozen-scaffold blanket, the wiring hub, and the other sources
 * {@link PlanCompileGraph#project} enumerates); everything else is read precisely off the plan's
 * relations, and the three-leg oracle in the incremental harness prices both directions: an
 * under-approximation fails the emit-to-graph leg, an undeclared over-approximation fails the
 * bounded-gap leg.
 */
public interface CompileDependencyGraph {

    /** Every generated compilation unit in the graph. */
    Set<UnitRef> nodes();

    /**
     * The generated units {@code node} references directly (forward edges). Never includes
     * references into consumer code, jOOQ tables, or dependency jars: those are already compiled
     * and on the resolved classpath, so they are not nodes. Returns an empty set for an unknown
     * node.
     */
    Set<UnitRef> directReferences(UnitRef node);

    /**
     * The generated units that reference {@code node} directly (reverse edges), the one-hop
     * dependents. The incremental engine walks these transitively to build the recompile set.
     * Returns an empty set for an unknown node.
     */
    Set<UnitRef> directDependents(UnitRef node);
}
