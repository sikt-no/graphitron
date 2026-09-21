package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.catalog.GraphPartition;
import org.jooq.DSLContext;

/**
 * H2's per-column statistics, as the operations a fact-store fixture needs: put a store back to
 * knowing nothing, and ask whether one relation has been analysed. Its own type because several
 * callers ask the same questions of the same engine metadata, and a second spelling of
 * "unanalysed" would let one of them pass while another failed.
 *
 * <p>It lives beside {@link ThreadConfinedStore} rather than beside the measurements because the
 * clear is its first caller and the most important one. A borrowed store is truncated of its rows
 * between cases, and selectivity survives a truncate, so a store cold in its rows and warm in its
 * statistics is what a case would otherwise be handed. That is the residue the clear's own leak
 * guard exists to refuse, and it fails the same way: quietly, and only when some other case ran
 * first.
 *
 * <p>{@link #UNANALYSED} is the value H2 assumes for a column it has never looked at, and it is a
 * real stored value rather than an absence: {@code ALTER TABLE ... ALTER COLUMN ... SELECTIVITY 0}
 * is the reset, and H2 stores that as fifty rather than as zero, so the reset states "no statistics"
 * rather than a third state of its own. Verified on 2.4.240: a hundred-row table reports fifty on
 * every column before {@code ANALYZE} and its real distinctness after, one for a single-valued
 * column and a hundred for a unique one.
 *
 * <p><b>One column is never in that state, and both operations here turn on it.</b> A created store
 * declares {@link GraphPartition#DECLARED_SELECTIVITY} on the partition column of every graph-keyed
 * base table, before any capture and before any {@code ANALYZE}, because a refresh planning inside a
 * transaction has no other way to be told what that column holds. So the partition column reports a
 * stated value on a store that has never been analysed, and it reports it again after the reset
 * below.
 */
public final class StoreStatistics {

    /** What H2 reports for a column no {@code ANALYZE} has looked at, on every base table. */
    public static final int UNANALYSED = 50;

    private StoreStatistics() {}

    /**
     * Puts every base table's every column back to {@link #UNANALYSED} and restates the partition
     * declaration a created store carries. Views are skipped because a view has no selectivity of
     * its own to state.
     *
     * <p>The restatement is what makes this a cold <em>store</em> rather than a state no store can
     * be in. A bare reset models a store created before the declaration existed: every plan measured
     * against it is a plan no run can get, and a regime built on one would report the declaration's
     * own effect as the baseline.
     */
    public static void reset(DSLContext dsl) {
        resetEveryColumn(dsl);
        declarePartitionSelectivity(dsl);
    }

    /**
     * Puts every base table's every column back to {@link #UNANALYSED}, the partition column
     * included: the state a store created before the declaration existed was in, which no store can
     * be in now. Only a measurement of what the declaration itself is worth has any business asking
     * for it, and {@code PartitionSelectivityWorthTest} is the one that does.
     */
    public static void resetIncludingTheDeclaration(DSLContext dsl) {
        resetEveryColumn(dsl);
    }

    /** Every base table's every column back to {@link #UNANALYSED}, the partition column included. */
    private static void resetEveryColumn(DSLContext dsl) {
        dsl.fetch("""
            SELECT c.TABLE_NAME, c.COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS c
            JOIN INFORMATION_SCHEMA.TABLES t
              ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
            WHERE c.TABLE_SCHEMA = 'PUBLIC' AND t.TABLE_TYPE = 'BASE TABLE'
            ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION
            """).forEach(row -> dsl.execute("ALTER TABLE \"" + row.get(0)
                + "\" ALTER COLUMN \"" + row.get(1) + "\" SELECTIVITY 0"));
    }

    /** The boot-time sweep's statement, restated: what {@code GraphitronModelStore.create} issues. */
    public static void declarePartitionSelectivity(DSLContext dsl) {
        GraphPartition.keyedBaseTables(dsl).forEach(relation ->
            dsl.execute("ALTER TABLE \"" + relation + "\" ALTER COLUMN \"" + GraphPartition.COLUMN
                + "\" SELECTIVITY " + GraphPartition.DECLARED_SELECTIVITY));
    }

    /**
     * Whether {@code relation} carries statistics: whether any column of it other than the partition
     * column reports something other than the value H2 assumes for a column it has never analysed.
     *
     * <p>Asked of the whole relation rather than of one chosen column, because which column moves is
     * the relation's business and a test naming one would be pinning its shape. The partition column
     * is excluded for the opposite reason: it is the one column whose value is stated rather than
     * measured, so it reports a non-default value from the instant the schema is created and would
     * make every never-analysed graph-keyed relation read as analysed. Read through
     * {@link GraphPartition#COLUMN} rather than spelled here, that being the whole point of the
     * column having one home.
     *
     * <p>With that exclusion the reading is exact in the direction that matters: an unanalysed
     * relation reports {@link #UNANALYSED} on every remaining column, so a false "analysed" is
     * impossible. The other direction has a theoretical hole, a relation whose every column
     * genuinely analyses to fifty, which would fail loudly rather than pass wrongly.
     */
    public static boolean analysed(DSLContext dsl, String relation) {
        return dsl.fetch("""
            SELECT SELECTIVITY FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND COLUMN_NAME <> ?
            """, relation.toUpperCase(), GraphPartition.COLUMN)
            .stream()
            .anyMatch(row -> row.get(0, Integer.class) != UNANALYSED);
    }
}
