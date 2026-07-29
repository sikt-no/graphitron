package no.sikt.graphitron.facts;

/**
 * The subject positions the shared fact traversal dispatches, one constant per callback on
 * {@link FactVisitor}. Unlike the lint engine's node kinds (syntactic AST slots), these are the
 * domain subjects a gathered fact is keyed by; the traversal reaches them through the same
 * reachability walk classification uses, so the population a visitor sees is exactly the
 * classified surface.
 *
 * <p>The registry-coverage meta-test asserts that every constant here is either subscribed by
 * some registered visitor or explicitly listed in {@link FactVisitors#NOT_GATHERED}, with no
 * overlap, so a new dispatch position must be claimed or waived, never silently ignored.
 */
public enum FactSubjectKind {

    /** A reachable output composite (object or interface type). */
    OUTPUT_TYPE,

    /** One field coordinate on a reachable output composite: the per-coordinate fact grain. */
    FIELD_COORDINATE,

    /** One member field of a reachable input object type. */
    INPUT_OBJECT_FIELD,
}
