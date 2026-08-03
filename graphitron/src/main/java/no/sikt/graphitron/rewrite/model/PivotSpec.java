package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.Objects;

/**
 * The target and join halves of a {@code @pivot} field, shared by both delivery leaves
 * ({@link ChildField.PivotField} inline, {@link ChildField.BatchedPivotField} batched): the
 * single FK hop to the attribute table, the projection type, and its slot leaves. The aggregate
 * operation's own parameters (the discriminator, value column, token map and their table) live
 * on the coordinate's {@link OperationMember.Pivot} member row, carried by the same leaves;
 * {@link #checkMemberAgreement} pins the two records to one coordinate from both leaf
 * constructors. Every pivot fact lives on the consuming field, never on the projection type:
 * the same plain output type is reused across pivots that resolve different value columns and
 * token vocabularies, so the type itself stays context-free (an ordinary
 * {@link GraphitronType.NestingType}).
 *
 * @param joinPath the resolved {@code @reference} path from the parent table to the attribute
 *     table. A single FK hop ({@link JoinStep.Hop} with {@link On.ColumnPairs}): the batched
 *     delivery's one-record-per-parent invariant requires the whole parent-input → terminus chain
 *     to be key-preserving, which the single left join guarantees only for this shape.
 * @param projectionTypeName the plain output type whose fields are the projection slots.
 * @param slots one {@link ChildField.PivotSlotField} per field of the projection type, in SDL
 *     order. These are the leaves the pivot edge contributes to the nested-type fetcher wiring,
 *     exactly as {@link ChildField.NestingField#nestedFields()} does.
 */
public record PivotSpec(
    List<JoinStep> joinPath,
    String projectionTypeName,
    List<ChildField.PivotSlotField> slots
) {
    public PivotSpec {
        joinPath = List.copyOf(joinPath);
        slots = List.copyOf(slots);
        Objects.requireNonNull(projectionTypeName, "projectionTypeName");
        if (!(joinPath.size() == 1 && joinPath.get(0) instanceof JoinStep.Hop hop
                && hop.on() instanceof On.ColumnPairs)) {
            throw new IllegalArgumentException(
                "PivotSpec.joinPath must be a single FK hop (JoinStep.Hop with On.ColumnPairs); "
                + "the classifier rejects multi-hop and condition-join paths under @pivot");
        }
    }

    /** The single FK hop to the attribute table (the shape the compact constructor pins). */
    public JoinStep.Hop hop() {
        return (JoinStep.Hop) joinPath.get(0);
    }

    /** The FK column pairs of {@link #hop()}: the parent → attribute-table correlation. */
    public On.ColumnPairs pairs() {
        return (On.ColumnPairs) hop().on();
    }

    /**
     * The cross-record invariants of one pivot coordinate, invoked from both delivery leaves'
     * compact constructors (the shared-static idiom of
     * {@link ParentCorrelation#checkCarrierInvariant}): the member's table is the join
     * terminus this spec's hop lands on, and every slot resolves to a token, so a member and a
     * spec that describe different pivots cannot share a leaf.
     */
    public static void checkMemberAgreement(OperationMember.Pivot pivot, PivotSpec spec, String carrier) {
        Objects.requireNonNull(pivot, "pivot");
        Objects.requireNonNull(spec, "spec");
        if (!pivot.table().denotesSameTableAs(spec.hop().targetTable())) {
            throw new IllegalArgumentException(
                carrier + ": the pivot member's table '" + pivot.table().tableName()
                + "' is not the join-path terminus '" + spec.hop().targetTable().tableName()
                + "'; the classifier resolves both against one attribute table");
        }
        for (var slot : spec.slots()) {
            if (!pivot.tokenBySlot().containsKey(slot.name())) {
                throw new IllegalArgumentException(
                    carrier + ": the pivot member's tokenBySlot is missing slot '" + slot.name()
                    + "'; the classifier resolves every slot to a token before construction");
            }
        }
    }
}
