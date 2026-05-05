package no.sikt.graphitron.rewrite.model;

/**
 * One cell of a join's column-pairing relationship between the source and target tables.
 *
 * <p>A {@link JoinStep.WithTarget} carries a {@code List} of these on its {@code slots} component,
 * with two permits.
 *
 * <ul>
 *   <li>{@link FkSlot} pairs an FK column on the source side against the corresponding
 *       referenced/referencing column on the target side, oriented at synthesis time. The
 *       FK-direction question (which end of the FK lives on the source table, which on the
 *       target table) is answered once in {@link BuildContext#synthesizeFkJoin} and baked
 *       into the slot pair; downstream readers are direction-blind.</li>
 *   <li>{@link LifterSlot} carries the load-bearing fact "DataLoader key tuple IS the
 *       target-column tuple" as a type identity: a single {@link ColumnRef} component whose
 *       value is returned by both {@link #sourceSide()} and {@link #targetSide()}.</li>
 * </ul>
 *
 * <p>Source and target name the <b>field's</b> endpoint tables: source is the table the parent
 * type is bound to (the hop's traversal entry), target is the table the target type is bound to
 * (the hop's traversal exit). For a chain hop {@code n > 0}, source is hop {@code n-1}'s target.
 * This generalises across every hop without leaning on the parent/child framing that gets fuzzy
 * past hop 0.
 */
public sealed interface JoinSlot permits JoinSlot.FkSlot, JoinSlot.LifterSlot {

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

    /**
     * Lifter pairing: the DataLoader key tuple equals the target-column tuple by construction,
     * so a single {@link ColumnRef} captures both sides of the slot. {@link #sourceSide()} and
     * {@link #targetSide()} both return {@link #column()}; this turns the prose-only invariant
     * "key tuple IS target-column tuple" into a type fact (one component, structural equality
     * of the two sides by definition).
     */
    record LifterSlot(ColumnRef column) implements JoinSlot {
        @Override public ColumnRef sourceSide() { return column; }
        @Override public ColumnRef targetSide() { return column; }
    }
}
