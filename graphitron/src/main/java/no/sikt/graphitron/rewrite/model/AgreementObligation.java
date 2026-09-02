package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * One SQL column that two UPDATE input fields both decode a value for, and the walker's finished
 * decision that the two must be checked equal before any DML runs.
 *
 * <p>The shape arises whenever a reference carrier lands on a column the matched key also covers.
 * Two carriers reach it:
 *
 * <ul>
 *   <li>A <em>self-FK</em> {@code @nodeId @reference} routes its lifted columns wholly to SET, so a
 *       column it shares with the identity field sits in both partitions. The reference side both
 *       writes the column and is checked.</li>
 *   <li>A <em>straddling cross-table</em> {@code @nodeId} reference partitions per column: its
 *       out-of-key columns are SET writes and its in-key columns are identity. Where such an in-key
 *       column already has an identity contributor, the reference side neither filters nor writes
 *       it and exists only to be checked.</li>
 * </ul>
 *
 * <p>Either way the foreign key forces the two values equal for well-formed input, and either way a
 * malformed input can disagree, because both values arrive on the wire. Disagreement is therefore a
 * runtime error and can only be a runtime error; the emitters lower each row to a
 * {@code NodeIdEncoder.requireColumnAgreement} call naming both input fields.
 *
 * <p>This is a walker decision rather than an emitter one because the carrier has four emit
 * consumers in {@code TypeFetcherGenerator} (direct-return and payload-returning, each single-row
 * and bulk) and only two of them used to intersect the partitions for themselves. A consumer can
 * drop a fact it has to re-derive without any compile error; folding over a stated one is the same
 * work at every site.
 *
 * @param column the SQL column both sides supply
 * @param keySide the contributor that supplies the column's WHERE predicate
 * @param referenceSide the reference carrier that also decodes a value for it
 */
public record AgreementObligation(ColumnRef column, Side keySide, Side referenceSide) {

    public AgreementObligation {
        if (column == null) {
            throw new IllegalArgumentException("AgreementObligation requires a column");
        }
        if (keySide == null || referenceSide == null) {
            throw new IllegalArgumentException(
                "AgreementObligation on column '" + column.sqlName() + "' requires both sides");
        }
        if (keySide.sdlFieldName().equals(referenceSide.sdlFieldName())) {
            throw new IllegalArgumentException(
                "AgreementObligation on column '" + column.sqlName() + "' names input field '"
                + keySide.sdlFieldName() + "' on both sides; a field cannot disagree with itself");
        }
    }

    /**
     * One contributor's half of an obligation: which input field it is, how the emitter reads its
     * wire value, and which slot of that field's decode record holds this column's value.
     *
     * <p>The slot is carried for the reason {@link ColumnOverlap.SlotColumn} gives: a straddling
     * reference's checked column can sit at any slot of a decode record whose other slots went to
     * the other partition, so no side's position in a partition recovers it.
     */
    public record Side(String sdlFieldName, CallSiteExtraction extraction, int decodeSlot) {
        public Side {
            if (decodeSlot < 0) {
                throw new IllegalArgumentException(
                    "AgreementObligation.Side '" + sdlFieldName + "' decodeSlot cannot be negative: "
                    + decodeSlot);
            }
        }
    }
}
