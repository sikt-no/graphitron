package no.sikt.graphitron.command;

/**
 * Result cardinality of a {@link CallWrap.Multiset} call: how many callee rows the wrapped
 * subselect may yield per outer row. Decides the subselect's LIMIT and the read-side unwrap,
 * never the wrap itself; both cardinalities render {@code DSL.multiset(...)} uniformly because
 * jOOQ 3.20's {@code DSL.row(Collection)} flattens nested aliased fields at render time.
 *
 * <p>An enum, not a sealed interface: neither value carries data. Promote to a sealed pair when
 * an arm first needs a payload.
 */
public enum Arity {
    /** Many rows; the subselect honours the field's own runtime limit, if any. */
    LIST,
    /** At most one row; the subselect caps at {@code .limit(1)} and the reader unwraps. */
    SINGLE
}
