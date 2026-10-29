package no.sikt.graphitron.rewrite.model;

import java.util.List;
import no.sikt.graphitron.model.diagnostics.MatchedKey;
import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * The DELETE-shape walker carrier. Holds the WHERE columns {@code DeleteRowsWalker} derived
 * from the DML input plus, on the {@link Identified} arm, the matched key identity that
 * proves the WHERE reduces to at most one row. Rides the {@link OperationMember.Write.Delete} arm alongside the slim
 * {@link InputArgRef} arg surface.
 *
 * <p><b>Why DELETE is not {@link UpdateRows}-minus-SET.</b> UPDATE partitions its input columns:
 * the matched key goes to WHERE, everything else to SET; the key is a partition boundary. DELETE
 * has no SET destination, so <em>every</em> admitted input column is a WHERE filter and the matched
 * key is a cardinality <em>guard</em> (it proves single-row), not a column subset. Every admitted
 * input column is a WHERE filter or dropped: {@link #whereColumns()} is the full admitted-column
 * set except where a node id covers the whole matched key plus more, whose other columns are
 * {@link #droppedColumns()}. Non-key filter columns from any other carrier are legitimate extra
 * ANDed predicates rather than orphans with nowhere to go. The family reuses the
 * {@link UpdateRows} family's {@link KeyColumn} for each WHERE contribution.
 *
 * <p>The family is sealed on two arms, unlike {@link UpdateRows}'s single {@link UpdateRows.Identified}:
 * {@link UpdateRows} rejects {@code multiRow: true} outright (covering a PK/UK is <em>the</em> single-row
 * UPDATE shape), but {@code multiRow: true} is a real DELETE shape ({@code deleteFilmsByReleaseYear} filters
 * on the non-PK {@code release_year}), so the carrier needs a {@link Broadcast} arm for it. The
 * {@link Broadcast} compact constructor rejects an empty {@link #whereColumns()} so an empty input
 * cannot degenerate into an unfiltered {@code DELETE}.
 *
 * <p>A third arm, {@link IdempotentByPrimaryKey}, is the {@code missingAsDeleted: true} refinement
 * of {@link Identified}: the matched key is the primary key and the input binds each of its
 * columns exactly once and nothing else, so a key that matches no row can be echoed from the input
 * as if it had been deleted. Outside the payload-returning emitters it reads exactly like
 * {@link Identified}.
 */
public sealed interface DeleteRows permits DeleteRows.Identified, DeleteRows.Broadcast, DeleteRows.IdempotentByPrimaryKey {

    /** Every admitted input column the statement filters on. Non-empty on every arm. */
    List<KeyColumn> whereColumns();

    /**
     * The columns an identifying carrier decodes but the statement does not filter on: the
     * out-of-key columns of a node id whose node key covers the whole matched key plus more. The
     * matched key alone names the row, so an id whose extra columns have since changed still deletes
     * it. Each keeps its decode slot. Empty on {@link Broadcast}, which has no matched key. Every
     * admitted column is in exactly one of {@link #whereColumns()} and this list.
     */
    List<KeyColumn> droppedColumns();

    /**
     * The input covers a primary key or unique key, so the WHERE reduces to at most one row
     * regardless of any {@code multiRow} flag. {@link #matchedKey()} is the single-row guard, not a
     * column subset; {@link #whereColumns()} is still every admitted input column.
     */
    record Identified(MatchedKey matchedKey, List<KeyColumn> whereColumns, List<KeyColumn> droppedColumns)
            implements DeleteRows {
        public Identified {
            if (matchedKey == null) {
                throw new IllegalArgumentException("matchedKey required");
            }
            whereColumns = List.copyOf(whereColumns);
            droppedColumns = List.copyOf(droppedColumns);
            if (whereColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "Identified.whereColumns cannot be empty; a covered key contributes at least "
                    + "one WHERE column");
            }
        }
    }

    /**
     * {@code missingAsDeleted: true}: the input identifies rows by exactly the primary key, so an
     * input key that matches no row counts as already deleted and its payload slot echoes the key
     * the client sent. {@link #keyBinding()} names, for each primary-key column in
     * {@code key.columns()} order, the one input contribution that supplies it; that is the order
     * of the {@code RecordN} the echo builds, independent of the order the WHERE compares the
     * columns in. {@link #whereColumns()} answers {@link #keyBinding()}: the input filters on no
     * other column, though a node id covering the primary key plus more contributes its other
     * columns to {@link #droppedColumns()}.
     *
     * <p>{@link #echoBinding()} narrows {@link #droppedColumns()} to the columns a missing-row echo
     * must supply beyond the primary key: the payload's node-key columns outside the primary key,
     * in node-key order, each bound to the dropped column that decodes it. The echo carries the
     * client's value for them, so an echoed id is the id the client sent, in the current key shape
     * (an id in a legacy shape is converted as it is decoded). Empty when the payload's
     * ids need no column beyond the primary key. Every echo binding's extraction is one some
     * {@link #keyBinding()} entry shares, so the decode the echo reads is one the WHERE already
     * performs.
     */
    record IdempotentByPrimaryKey(
        MatchedKey.PrimaryKey key,
        List<KeyColumn> keyBinding,
        List<KeyColumn> droppedColumns,
        List<KeyColumn> echoBinding
    ) implements DeleteRows {
        public IdempotentByPrimaryKey {
            if (key == null) {
                throw new IllegalArgumentException("key required");
            }
            keyBinding = List.copyOf(keyBinding);
            droppedColumns = List.copyOf(droppedColumns);
            echoBinding = List.copyOf(echoBinding);
            for (var echo : echoBinding) {
                if (!droppedColumns.contains(echo)) {
                    throw new IllegalArgumentException(
                        "IdempotentByPrimaryKey.echoBinding binds '" + echo.targetColumn().sqlName()
                        + "', which is not among the dropped columns");
                }
                if (keyBinding.stream().noneMatch(kc -> kc.extraction().equals(echo.extraction()))) {
                    throw new IllegalArgumentException(
                        "IdempotentByPrimaryKey.echoBinding '" + echo.targetColumn().sqlName()
                        + "' reads input field '" + echo.sdlFieldName() + "', which supplies no "
                        + "primary-key column; the echo must read a decode the WHERE performs");
                }
            }
            List<ColumnRef> keyColumns = key.columns();
            if (keyBinding.size() != keyColumns.size()) {
                throw new IllegalArgumentException(
                    "IdempotentByPrimaryKey.keyBinding must bind each of the " + keyColumns.size()
                    + " primary-key columns exactly once, got " + keyBinding.size() + " bindings");
            }
            for (int i = 0; i < keyColumns.size(); i++) {
                String expected = keyColumns.get(i).sqlName();
                String actual = keyBinding.get(i).targetColumn().sqlName();
                if (!expected.equals(actual)) {
                    throw new IllegalArgumentException(
                        "IdempotentByPrimaryKey.keyBinding[" + i + "] binds '" + actual
                        + "' where the primary key's column in that position is '" + expected + "'");
                }
            }
        }

        @Override
        public List<KeyColumn> whereColumns() {
            return keyBinding;
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

        /** No matched key, so nothing is dropped: every admitted column is a filter. */
        @Override
        public List<KeyColumn> droppedColumns() {
            return List.of();
        }
    }
}
