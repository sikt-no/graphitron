package no.sikt.graphitron.rewrite.model;

import java.util.List;
import no.sikt.graphitron.model.diagnostics.MatchedKey;
import no.sikt.graphitron.model.diagnostics.UpdateRowsError;

/**
 * The UPDATE-shape walker carrier. Holds the matched key identity plus the SET and WHERE
 * column partitions {@code UpdateRowsWalker} derived from the DML input and the jOOQ
 * catalog, and the value-agreement obligations the two partitions' overlap produces.
 * Rides the {@link OperationMember.Write.Update} arm alongside the slim {@link InputArgRef} arg surface.
 *
 * <p>The family is sealed with one arm today ({@link Identified}); keeping it sealed rather than
 * collapsing to a bare record leaves room for a future UPDATE shape without reworking consumers.
 * The walker deliberately rejects {@code multiRow: true} upstream, so no {@code Broadcast} arm is
 * planned. The {@link Identified} compact constructor makes the non-empty-SET promise load-bearing
 * on the type system: the walker rejects empty-SET inputs with
 * {@link UpdateRowsError.NoSetFields} before any carrier is constructed.
 */
public sealed interface UpdateRows permits UpdateRows.Identified {

    MatchedKey matchedKey();

    List<SetColumn> setColumns();

    List<KeyColumn> keyColumns();

    /**
     * The columns two input fields both decode a value for, each to be checked equal before the DML
     * runs. Empty for the common shape, where no reference carrier lands on a matched-key column.
     * See {@link AgreementObligation} for which carriers produce a row.
     */
    List<AgreementObligation> agreementObligations();

    /**
     * What an explicit {@code null} on each SET-contributing input field means, one row per such
     * carrier. Stated here rather than left to the emitters because the answer turns on the matched
     * key, which only the walker holds. See {@link CarrierNullRule}.
     */
    List<CarrierNullRule> nullRules();

    record Identified(
        MatchedKey matchedKey,
        List<SetColumn> setColumns,
        List<KeyColumn> keyColumns,
        List<AgreementObligation> agreementObligations,
        List<CarrierNullRule> nullRules
    ) implements UpdateRows {
        public Identified {
            if (matchedKey == null) {
                throw new IllegalArgumentException("matchedKey required");
            }
            setColumns = List.copyOf(setColumns);
            keyColumns = List.copyOf(keyColumns);
            agreementObligations = List.copyOf(agreementObligations);
            nullRules = List.copyOf(nullRules);
            if (setColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "Identified.setColumns cannot be empty; the walker rejects empty-SET inputs "
                    + "with UpdateRowsError.NoSetFields before constructing the carrier");
            }
            // One rule per carrier is the grain; two rules for one carrier would let an emitter
            // resolving a SET group to its rule get either answer depending on lookup order.
            var seen = new java.util.HashSet<String>();
            for (var r : nullRules) {
                if (!seen.add(r.sdlFieldName() + "\0" + r.extraction())) {
                    throw new IllegalArgumentException(
                        "Identified.nullRules carries two rules for input field '"
                        + r.sdlFieldName() + "'; the rule is stated once per SET carrier");
                }
            }
        }
    }
}
