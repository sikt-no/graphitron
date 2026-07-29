package no.sikt.graphitron.command;

/**
 * The shape a launcher's query returns, derived once by the producer from the coordinate's
 * cardinality so the renderer reads a return shape instead of deriving one: a single
 * {@code org.jooq.Record} fetched with {@code fetchOne()}, or a {@code Result<Record>} fetched
 * with {@code fetch()} under the composition's ordering.
 *
 * <p>An enum today; the connection arm, which carries a payload (the seek pagination and the
 * carrier plan), promotes this to a sealed interface when its first row lands with the
 * connection slice. Value-identical to {@link Arity} in this slice but not the same fact: this
 * is a launcher's return type (and the coming connection arm is not an arity at all), where
 * {@code Arity} is a multiset contribution's unwrap decision; the two must not be fused.
 */
public enum ResultShape {
    /** One record or null: the {@code fetchOne()} shape, unordered by construction. */
    SINGLE_RECORD,
    /** A record list: the {@code fetch()} shape, ordered by the command's ordering slot. */
    RECORD_LIST
}
