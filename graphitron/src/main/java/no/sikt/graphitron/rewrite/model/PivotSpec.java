package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The facts a {@code @pivot} projection needs, shared by both delivery leaves
 * ({@link ChildField.PivotField} inline, {@link ChildField.BatchedPivotField} batched). Every
 * pivot fact lives here on the consuming field, never on the projection type: the same plain
 * output type is reused across pivots that resolve different value columns and token
 * vocabularies, so the type itself stays context-free (an ordinary
 * {@link GraphitronType.NestingType}).
 *
 * @param joinPath the resolved {@code @reference} path from the parent table to the attribute
 *     table. A single FK hop ({@link JoinStep.Hop} with {@link On.ColumnPairs}): the batched
 *     delivery's one-record-per-parent invariant requires the whole parent-input → terminus chain
 *     to be key-preserving, which the single left join guarantees only for this shape. Enforced by
 *     the compact constructor; the classifier rejects other shapes before construction.
 * @param pivotTable the attribute table the path terminates at (the join terminus both
 *     {@link #discriminator()} and {@link #value()} resolve against).
 * @param discriminator the resolved {@code on:} column: each selected slot projects
 *     {@code max(value) FILTER (WHERE discriminator = token)}.
 * @param value the resolved {@code value:} column whose per-token aggregate fills each slot.
 * @param projectionTypeName the plain output type whose fields are the projection slots.
 * @param slots one {@link ChildField.PivotSlotField} per field of the projection type, in SDL
 *     order. These are the leaves the pivot edge contributes to the nested-type fetcher wiring,
 *     exactly as {@link ChildField.NestingField#nestedFields()} does.
 * @param tokenBySlot the resolved slot → discriminator-token map, keyed by slot SDL name. Built at
 *     classify time: from the {@code vocabulary:} enum's text mapping when given, by identity
 *     (token = slot name) when omitted. Consumed only where the projection subselect is built; the
 *     token never reaches the slot leaf.
 */
public record PivotSpec(
    List<JoinStep> joinPath,
    TableRef pivotTable,
    ColumnRef discriminator,
    ColumnRef value,
    String projectionTypeName,
    List<ChildField.PivotSlotField> slots,
    Map<String, String> tokenBySlot
) {
    public PivotSpec {
        joinPath = List.copyOf(joinPath);
        slots = List.copyOf(slots);
        tokenBySlot = Map.copyOf(tokenBySlot);
        Objects.requireNonNull(pivotTable, "pivotTable");
        Objects.requireNonNull(discriminator, "discriminator");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(projectionTypeName, "projectionTypeName");
        if (!(joinPath.size() == 1 && joinPath.get(0) instanceof JoinStep.Hop hop
                && hop.on() instanceof On.ColumnPairs)) {
            throw new IllegalArgumentException(
                "PivotSpec.joinPath must be a single FK hop (JoinStep.Hop with On.ColumnPairs); "
                + "the classifier rejects multi-hop and condition-join paths under @pivot");
        }
        for (var slot : slots) {
            if (!tokenBySlot.containsKey(slot.name())) {
                throw new IllegalArgumentException(
                    "PivotSpec.tokenBySlot is missing slot '" + slot.name()
                    + "'; the classifier resolves every slot to a token before construction");
            }
        }
    }

    /** The single FK hop to the attribute table (the shape the compact constructor pins). */
    public JoinStep.Hop hop() {
        return (JoinStep.Hop) joinPath.get(0);
    }

    /** The FK column pairs of {@link #hop()} — the parent → attribute-table correlation. */
    public On.ColumnPairs pairs() {
        return (On.ColumnPairs) hop().on();
    }
}
