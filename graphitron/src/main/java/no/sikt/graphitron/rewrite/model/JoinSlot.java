package no.sikt.graphitron.rewrite.model;

/**
 * One cell of a join's column-pairing relationship between the source and target tables.
 *
 * <p>{@link On.ColumnPairs} holds a {@code List} of these on its {@code slots} component.
 * {@link FkSlot} is the sole permit (R431: the {@code LifterSlot} permit — a single column
 * answering both sides, "DataLoader key tuple IS the target-column tuple" — moved with
 * {@code JoinStep.LiftedHop} onto {@link ParentCorrelation.OnLiftedSlots}, which carries the
 * column tuple directly).
 *
 * <p>{@link FkSlot} pairs an FK column on the source side against the corresponding
 * referenced/referencing column on the target side, oriented at synthesis time. The
 * FK-direction question (which end of the FK lives on the source table, which on the
 * target table) is answered once in {@link BuildContext#synthesizeFkJoin} and baked
 * into the slot pair; downstream readers are direction-blind.
 *
 * <p>Source and target name the <b>field's</b> endpoint tables: source is the table the parent
 * type is bound to (the hop's traversal entry), target is the table the target type is bound to
 * (the hop's traversal exit). For a chain hop {@code n > 0}, source is hop {@code n-1}'s target.
 * This generalises across every hop without leaning on the parent/child framing that gets fuzzy
 * past hop 0.
 */
public sealed interface JoinSlot permits JoinSlot.FkSlot {

    /** Column on the hop's source table — the side the join is entered <em>from</em>. */
    ColumnRef sourceSide();

    /** Column on the hop's target table — the side the join lands <em>on</em>. */
    ColumnRef targetSide();

    /**
     * Catalog-FK pairing: one slot of a foreign-key constraint, with the source-side and
     * target-side columns identified at synthesis time. Replaces the parallel
     * {@code sourceColumns[i]} / {@code targetColumns[i]} pairing that previously lived
     * across two ordered lists with an "expect equal arity" precondition.
     */
    record FkSlot(ColumnRef sourceSide, ColumnRef targetSide) implements JoinSlot {}
}
