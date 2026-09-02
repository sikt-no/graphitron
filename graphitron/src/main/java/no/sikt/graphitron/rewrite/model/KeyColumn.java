package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * One column contribution on the WHERE side of an UPDATE or DELETE: a filter column the
 * input fills. Carries the GraphQL input field name it came from, the jOOQ column it fills, how
 * to read the input value at the call-site root, and which slot of the decode record that value
 * sits in. On UPDATE every {@code KeyColumn} is a matched-key
 * column (the WHERE partition); on DELETE it is any admitted input column, since DELETE
 * has no SET partition and every input column is a WHERE filter — the matched key there is a
 * single-row guard, not the WHERE-column set.
 *
 * <p>Like {@link SetColumn}, decoupled from {@link InputField}. The composite-NodeId case
 * maps one SDL input field to N {@code KeyColumn} rows sharing one {@link #sdlFieldName()}
 * but differing in {@link #targetColumn()}; the emitter groups by {@link #sdlFieldName()} to emit
 * one decode local that all N columns reference. {@link #extraction()} reuses the existing
 * {@link CallSiteExtraction} family ({@code Direct}, or arity-1 / arity-N
 * {@link CallSiteExtraction.NodeIdDecodeKeys}).
 *
 * <p>{@link #decodeSlot()} is the 0-based position of this column in the {@code Record<N>} the
 * carrier's {@code @nodeId} decode returns, and {@code 0} for a plain field that performs no
 * decode. It is stated rather than recovered from the row's position in the WHERE partition,
 * because a straddling cross-table reference splits one decode record across the WHERE and SET
 * sides and leaves each side a non-contiguous slice; see {@link ColumnOverlap.SlotColumn}.
 */
public record KeyColumn(
    String sdlFieldName,
    ColumnRef targetColumn,
    CallSiteExtraction extraction,
    int decodeSlot
) {
    public KeyColumn {
        if (decodeSlot < 0) {
            throw new IllegalArgumentException(
                "KeyColumn '" + sdlFieldName + "' decodeSlot cannot be negative: " + decodeSlot);
        }
    }
}
