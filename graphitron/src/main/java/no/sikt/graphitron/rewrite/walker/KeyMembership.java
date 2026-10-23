package no.sikt.graphitron.rewrite.walker;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.ColumnOverlap.SlotColumn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How one carrier's columns sit against the matched key: the one membership question both
 * {@link UpdateRowsWalker} and {@link DeleteRowsWalker} ask, answered once so the two walkers cannot
 * disagree on it. Each arm carries its columns with their decode slots, read off the carrier's
 * column index (the slot the carrier's decode record holds the column at), since once a carrier's
 * columns are split no partition's ordering recovers the slot.
 *
 * <p>What an arm means is each walker's own verdict. {@link CoversKeyPlus} is the one arm whose
 * meaning is shared: the carrier names the row by the whole matched key, so its other columns are
 * dropped, neither filtered on nor written.
 */
sealed interface KeyMembership {

    /** Every column is a matched-key column. */
    record AllInKey(List<SlotColumn> inKey) implements KeyMembership {
        public AllInKey { inKey = List.copyOf(inKey); }
    }

    /** No column is a matched-key column. */
    record NoneInKey(List<SlotColumn> outside) implements KeyMembership {
        public NoneInKey { outside = List.copyOf(outside); }
    }

    /** An own-columns carrier holding every matched-key column plus others: the node id of a type
     *  whose node key is a strict superset of the matched key. {@code dropped} is the others. */
    record CoversKeyPlus(List<SlotColumn> inKey, List<SlotColumn> dropped) implements KeyMembership {
        public CoversKeyPlus {
            inKey = List.copyOf(inKey);
            dropped = List.copyOf(dropped);
        }
    }

    /** Some matched-key columns and some others, without being an own-columns carrier of the whole
     *  key. A reference carrier covering the key plus more reports here, so references keep their
     *  per-column partitioning. */
    record Straddles(List<SlotColumn> inKey, List<SlotColumn> outside) implements KeyMembership {
        public Straddles {
            inKey = List.copyOf(inKey);
            outside = List.copyOf(outside);
        }
    }

    /**
     * Measure {@code columns} (positionally the carrier's decode slots) against the matched key's
     * SQL names. A self-FK is never measured: its columns point at a sibling row and are never this
     * row's identity, so asking how they sit against the key is a caller error.
     */
    static KeyMembership of(CarrierRole role, List<ColumnRef> columns, Set<String> keySqlNames) {
        if (role instanceof CarrierRole.SelfFk) {
            throw new IllegalArgumentException("a self-FK carrier is never measured against the matched key");
        }
        var inKey = new ArrayList<SlotColumn>();
        var outside = new ArrayList<SlotColumn>();
        var coveredKey = new LinkedHashSet<String>();
        for (int slot = 0; slot < columns.size(); slot++) {
            var col = columns.get(slot);
            if (keySqlNames.contains(col.sqlName())) {
                inKey.add(new SlotColumn(slot, col));
                coveredKey.add(col.sqlName());
            } else {
                outside.add(new SlotColumn(slot, col));
            }
        }
        if (outside.isEmpty()) {
            return new AllInKey(inKey);
        }
        if (inKey.isEmpty()) {
            return new NoneInKey(outside);
        }
        if (role instanceof CarrierRole.OwnColumns && coveredKey.equals(keySqlNames)) {
            return new CoversKeyPlus(inKey, outside);
        }
        return new Straddles(inKey, outside);
    }
}
