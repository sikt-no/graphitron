package no.sikt.graphitron.rewrite.model;

/**
 * The bound a written cell's value must respect before the statement runs, decided once per use
 * where the input field is resolved against the consuming field's write target. Use-keyed rather
 * than per input type: a {@code @mutation} input type carries no table, so one SDL input field can
 * write differently sized columns in two mutations.
 *
 * <p>{@link Length} is minted in exactly one place, {@code BuildContext.writeBound}, and only on a
 * cell whose raw wire value is the column's text: a direct, single-column, non-enum write into a
 * {@code java.lang.String}-bound column whose type declares a length. A NodeId-decoded cell is
 * always {@link Unbounded}, so the emitter can length-test the raw value without decoding it.
 * {@link SetColumn} copies the decision from the carrier it was derived from and never re-decides it.
 */
public sealed interface ColumnWriteBound permits ColumnWriteBound.Length, ColumnWriteBound.Unbounded {

    /** The cell writes the client's text unchanged into a column declaring {@code declaredLength}. */
    record Length(int declaredLength) implements ColumnWriteBound {
        public Length {
            if (declaredLength <= 0) {
                throw new IllegalArgumentException(
                    "ColumnWriteBound.Length requires a positive declared length; got " + declaredLength);
            }
        }
    }

    /** Anything else: no length check applies to the cell. */
    record Unbounded() implements ColumnWriteBound {
        public static final Unbounded INSTANCE = new Unbounded();
    }
}
