package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * One column contribution on the SET side of an UPDATE. Carries the GraphQL input field
 * name it came from, the jOOQ column it writes, how to read the input value at the call-site
 * root, and which slot of the decode record that value sits in.
 *
 * <p>Deliberately decoupled from {@link InputField}: the principle is that input fields have no
 * semantics independent of the consuming field, so the carrier names exactly what UPDATE's SET
 * partition needs and nothing else. A composite-NodeId input field that lifts to several columns
 * produces several {@code SetColumn} rows sharing one {@link #sdlFieldName()} (the emitter groups
 * by it to emit a single decode local the columns reference); {@link #extraction()} reuses the
 * existing {@link CallSiteExtraction} family so the emit-side decode helpers stay unchanged.
 *
 * <p>{@link #decodeSlot()} is the 0-based position of this column in the {@code Record<N>} the
 * carrier's {@code @nodeId} decode returns, and {@code 0} for a plain field that performs no
 * decode. It is stated rather than recovered from the row's position in the SET partition, because
 * a straddling cross-table reference splits one decode record across the WHERE and SET sides and
 * leaves each side a non-contiguous slice; see {@link ColumnOverlap.SlotColumn}.
 */
public record SetColumn(
    String sdlFieldName,
    ColumnRef targetColumn,
    CallSiteExtraction extraction,
    int decodeSlot
) {
    public SetColumn {
        if (decodeSlot < 0) {
            throw new IllegalArgumentException(
                "SetColumn '" + sdlFieldName + "' decodeSlot cannot be negative: " + decodeSlot);
        }
    }
}
