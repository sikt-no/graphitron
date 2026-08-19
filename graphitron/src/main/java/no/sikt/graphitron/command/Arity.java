package no.sikt.graphitron.command;

/**
 * How many rows one command's result may hold: the commands' one/many fact, stated once so no
 * family mints a second spelling of it.
 *
 * <p>Two readers today, and each decides something different from the same value. On a
 * {@link CallWrap.Multiset} call it is the callee rows per outer row, deciding the subselect's
 * LIMIT and the read-side unwrap, never the wrap itself (both cardinalities render
 * {@code DSL.multiset(...)} uniformly, because jOOQ 3.20's {@code DSL.row(Collection)} flattens
 * nested aliased fields at render time). On a {@link RoutineWriteCommand} it is the emitted
 * fetcher's delivery, deciding the fetch terminal and the declared value type.
 *
 * <p>An enum, not a sealed interface: neither value carries data. Promote to a sealed pair when
 * an arm first needs a payload.
 */
public enum Arity {
    /** Many rows: a subselect honours the field's own runtime limit, a fetcher fetches a result. */
    LIST,
    /** At most one row: a subselect caps at {@code .limit(1)} and its reader unwraps. */
    SINGLE
}
