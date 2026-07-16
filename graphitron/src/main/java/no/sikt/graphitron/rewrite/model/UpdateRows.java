package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * The UPDATE-shape walker carrier. Holds the matched key identity plus the SET and WHERE
 * column partitions {@code UpdateRowsWalker} derived from the {@code @table} input and the jOOQ
 * catalog. Lands on {@link UpdateRowsField} alongside the slim {@link InputArgRef} arg surface.
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

    record Identified(
        MatchedKey matchedKey,
        List<SetColumn> setColumns,
        List<KeyColumn> keyColumns
    ) implements UpdateRows {
        public Identified {
            if (matchedKey == null) {
                throw new IllegalArgumentException("matchedKey required");
            }
            setColumns = List.copyOf(setColumns);
            keyColumns = List.copyOf(keyColumns);
            if (setColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "Identified.setColumns cannot be empty; the walker rejects empty-SET inputs "
                    + "with UpdateRowsError.NoSetFields before constructing the carrier");
            }
        }
    }
}
