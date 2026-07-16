package no.sikt.graphitron.mcp;

/**
 * The relationship label on an {@link Edge}. Direction-independent: the same kind
 * labels both directions of a traversal (a forward {@code BACKS} edge field -> column and the
 * reverse {@code BACKS} edge column -> field carry the same kind; only the {@code direction} query
 * axis and the {@link Edge#target()} slot differ).
 *
 * <p>Legitimately an {@code enum} (a label), not a sealed hierarchy: the varying-shape part of an
 * edge (table vs column vs method vs type endpoint) lives in {@link NodeRef}, so {@link EdgeKind}
 * carries no kind-dependent nullability. This is the resolution of the sealed-over-enum tension the
 * Spec names.
 */
enum EdgeKind {
    /** The field's value comes from this column. Endpoint: a {@link NodeRef.ColumnNode}. */
    BACKS,
    /** The field / type binds this table directly (no FK hop). Endpoint: a {@link NodeRef.TableNode}. */
    TARGETS,
    /** The field / table reaches this table through an FK hop. Endpoint: a {@link NodeRef.TableNode}. */
    REFERENCES,
    /** The field is resolved by this consumer method. Endpoint: a {@link NodeRef.MethodNode}. */
    RESOLVES,
    /** An abstract type's / field's members (forward, stage-1 only). Endpoint: a {@link NodeRef.TypeNode}. */
    PARTICIPATES
}
