package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * The DELETE-shape walker carrier. Holds the WHERE columns {@code DeleteRowsWalker} derived
 * from the {@code @table} input plus, on the {@link Identified} arm, the matched key identity that
 * proves the WHERE reduces to at most one row. Lands on {@link DeleteRowsField} alongside the slim
 * {@link InputArgRef} arg surface.
 *
 * <p><b>Why DELETE is not {@link UpdateRows}-minus-SET.</b> UPDATE partitions its input columns:
 * the matched key goes to WHERE, everything else to SET; the key is a partition boundary. DELETE
 * has no SET destination, so <em>every</em> admitted input column is a WHERE filter and the matched
 * key is a cardinality <em>guard</em> (it proves single-row), not a column subset. {@link #whereColumns()}
 * is therefore the full admitted-column set on both arms; non-key filter columns are legitimate
 * extra ANDed predicates rather than orphans with nowhere to go. The family reuses R246's
 * {@link KeyColumn} for each WHERE contribution.
 *
 * <p>The family is sealed on two arms, unlike {@link UpdateRows}'s single {@link UpdateRows.Identified}:
 * R246 rejects {@code multiRow: true} outright (covering a PK/UK is <em>the</em> single-row UPDATE
 * shape), but {@code multiRow: true} is a real DELETE shape ({@code deleteFilmsByReleaseYear} filters
 * on the non-PK {@code release_year}), so the carrier needs a {@link Broadcast} arm for it. The
 * {@link Broadcast} compact constructor rejects an empty {@link #whereColumns()} so an empty input
 * cannot degenerate into an unfiltered {@code DELETE}.
 */
public sealed interface DeleteRows permits DeleteRows.Identified, DeleteRows.Broadcast {

    /** Every admitted input column, each a WHERE filter. Non-empty on both arms. */
    List<KeyColumn> whereColumns();

    /**
     * The input covers a primary key or unique key, so the WHERE reduces to at most one row
     * regardless of any {@code multiRow} flag. {@link #matchedKey()} is the single-row guard, not a
     * column subset; {@link #whereColumns()} is still every admitted input column.
     */
    record Identified(MatchedKey matchedKey, List<KeyColumn> whereColumns) implements DeleteRows {
        public Identified {
            if (matchedKey == null) {
                throw new IllegalArgumentException("matchedKey required");
            }
            whereColumns = List.copyOf(whereColumns);
            if (whereColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "Identified.whereColumns cannot be empty; a covered key contributes at least "
                    + "one WHERE column");
            }
        }
    }

    /**
     * {@code multiRow: true} and no primary key or unique key is covered: the DELETE broadcasts over
     * every row the WHERE matches. No {@link MatchedKey} — there is no single-row guarantee, which is
     * the whole point of {@code multiRow}. The compact constructor rejects an empty
     * {@link #whereColumns()} so the broadcast always carries at least one filter predicate.
     */
    record Broadcast(List<KeyColumn> whereColumns) implements DeleteRows {
        public Broadcast {
            whereColumns = List.copyOf(whereColumns);
            if (whereColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "Broadcast.whereColumns cannot be empty; an unfiltered DELETE is never emitted");
            }
        }
    }
}
