package no.sikt.graphitron.mcp;

import no.sikt.graphitron.rewrite.catalog.FieldClassification;

import java.util.List;

/**
 * One typed relationship plus the <em>other</em> node, never an untyped adjacency and
 * never a bag of per-kind nullable endpoint fields. The endpoint structure lives entirely in
 * {@link #target()} (a {@link NodeRef}); {@link #kind()} is just the label.
 *
 * <p>{@code joinPath} is the one relationship-level adjunct: the FK hops a {@link EdgeKind#REFERENCES}
 * edge traversed, empty for direct edges. It reuses the {@link FieldClassification.FkStep} the
 * classifier already carries on its reference / table-target arms, rather than re-deriving FK
 * structure at the edge layer.
 */
record Edge(EdgeKind kind, NodeRef target, List<FieldClassification.FkStep> joinPath) {

    Edge {
        joinPath = List.copyOf(joinPath);
    }

    /** A direct edge (no FK hops): {@code joinPath} empty. */
    static Edge direct(EdgeKind kind, NodeRef target) {
        return new Edge(kind, target, List.of());
    }
}
