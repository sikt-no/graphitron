package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * What an explicit {@code null} on one UPDATE input field means, decided once by
 * {@code UpdateRowsWalker} and stated on the {@link UpdateRows} carrier so no emitter re-derives it.
 *
 * <p>The question only arises for a carrier contributing to the SET partition, because clearing is
 * an assignment: a carrier whose columns are all identity has nothing to clear, and a carrier the
 * walker refused never reaches an emitter at all. One row per such carrier, named the way
 * {@link AgreementObligation.Side} names a contributor: the SDL field name plus the extraction, which
 * together give the wire access path the emitters group by.
 *
 * <p>The fact rides at carrier grain rather than on {@link SetColumn}, and deliberately not on the
 * emitter's own SET-group adapter. A component on {@code SetColumn} would repeat one carrier-grain
 * answer down N column rows with nothing able to see them disagree; the emitter's adapter is shared
 * with the INSERT plan and with the WHERE partition projected into the same shape, where a clear
 * disposition means nothing. One row per carrier keeps the fact at the grain the walker decided it.
 *
 * @param sdlFieldName the input field the rule is about
 * @param extraction how the emitter reads that field's wire value; with the name, the access path
 * @param rule what an explicit null on it does
 */
public record CarrierNullRule(String sdlFieldName, CallSiteExtraction extraction, OnExplicitNull rule) {

    public CarrierNullRule {
        if (sdlFieldName == null || sdlFieldName.isBlank()) {
            throw new IllegalArgumentException("CarrierNullRule requires an SDL field name");
        }
        if (extraction == null) {
            throw new IllegalArgumentException(
                "CarrierNullRule for input field '" + sdlFieldName + "' requires an extraction");
        }
        if (rule == null) {
            throw new IllegalArgumentException(
                "CarrierNullRule for input field '" + sdlFieldName + "' requires a rule");
        }
    }

    /**
     * The three answers, closed. The predicate producing them is uniform over every carrier shape and
     * never consults whether the carrier straddles the matched key: what decides is the SDL
     * nullability and whether any column the carrier <em>writes</em> is a matched-key column.
     */
    public sealed interface OnExplicitNull {

        /**
         * The SDL field is non-null, so GraphQL rejects a null before graphitron sees one. The
         * emitters produce exactly what they produced before this fact existed.
         */
        record CannotArrive() implements OnExplicitNull {}

        /**
         * The field is nullable and no column it writes is a matched-key column. An explicit null
         * writes {@code NULL} to every column the carrier contributes to SET, contributes no value
         * to any cross-partition agreement check, and leaves the WHERE partition alone.
         *
         * <p>A straddling cross-table reference admitted by the walker is always this: its SET half
         * is its out-of-key half by construction, so clearing it cannot touch the row's identity.
         * A half-null foreign key imposes no referential obligation under PostgreSQL's default
         * {@code MATCH SIMPLE}, so the cleared reference is an absent one rather than a dangling one.
         */
        record Clears() implements OnExplicitNull {}

        /**
         * The field is nullable and some column it writes <em>is</em> a matched-key column, so
         * clearing would null the row's own identity. Refused at runtime with a message naming the
         * columns. The self-FK overlap is what reaches this arm: a self-FK routes every lifted column
         * to SET, so one that also sits in the matched key is an ordinary assignment right up to the
         * point where the assigned value is null and the row would be orphaned.
         *
         * @param identityColumns the matched-key columns the carrier writes, which the message names
         */
        record RefusedAsIdentity(List<ColumnRef> identityColumns) implements OnExplicitNull {
            public RefusedAsIdentity {
                identityColumns = List.copyOf(identityColumns);
                if (identityColumns.isEmpty()) {
                    throw new IllegalArgumentException(
                        "RefusedAsIdentity requires the identity columns the refusal names");
                }
            }
        }
    }
}
