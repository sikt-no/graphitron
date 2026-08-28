package no.sikt.graphitron.rewrite.derive;

import org.jooq.DSLContext;

/**
 * H2's per-column statistics, as the two operations a statistics measurement over a fact store
 * needs: put a store back to knowing nothing, and ask whether one relation has been analysed. Its
 * own type because two tests in this package ask the same questions of the same engine metadata, and
 * a second spelling of "unanalysed" would let one of them pass while the other failed.
 *
 * <p>{@link #UNANALYSED} is the value H2 assumes for a column it has never looked at, and it is a
 * real stored value rather than an absence: {@code ALTER TABLE ... ALTER COLUMN ... SELECTIVITY 0}
 * is the reset, and H2 stores that as fifty rather than as zero, so the reset states "no statistics"
 * rather than a third state of its own. Verified on 2.4.240: a hundred-row table reports fifty on
 * every column before {@code ANALYZE} and its real distinctness after, one for a single-valued
 * column and a hundred for a unique one.
 */
final class StoreStatistics {

    /** What H2 reports for a column no {@code ANALYZE} has looked at, on every base table. */
    static final int UNANALYSED = 50;

    private StoreStatistics() {}

    /**
     * Puts every base table's every column back to {@link #UNANALYSED}. Views are skipped because a
     * view has no selectivity of its own to state.
     */
    static void reset(DSLContext dsl) {
        dsl.fetch("""
            SELECT c.TABLE_NAME, c.COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS c
            JOIN INFORMATION_SCHEMA.TABLES t
              ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
            WHERE c.TABLE_SCHEMA = 'PUBLIC' AND t.TABLE_TYPE = 'BASE TABLE'
            ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION
            """).forEach(row -> dsl.execute("ALTER TABLE \"" + row.get(0)
                + "\" ALTER COLUMN \"" + row.get(1) + "\" SELECTIVITY 0"));
    }

    /**
     * Whether {@code relation} carries statistics: whether any column of it reports something other
     * than the value H2 assumes for a column it has never analysed.
     *
     * <p>Asked of the whole relation rather than of one chosen column, because which column moves is
     * the relation's business and a test naming one would be pinning its shape. The reading is exact
     * in the direction that matters: an unanalysed relation reports {@link #UNANALYSED} on every
     * column, so a false "analysed" is impossible. The other direction has a theoretical hole, a
     * relation whose every column genuinely analyses to fifty, which would fail loudly rather than
     * pass wrongly.
     */
    static boolean analysed(DSLContext dsl, String relation) {
        return dsl.fetch("""
            SELECT SELECTIVITY FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ?
            """, relation.toUpperCase())
            .stream()
            .anyMatch(row -> row.get(0, Integer.class) != UNANALYSED);
    }
}
