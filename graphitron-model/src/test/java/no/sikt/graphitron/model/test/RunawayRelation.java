package no.sikt.graphitron.model.test;

import org.jooq.DSLContext;

/**
 * Replaces one of a store's relations with a view of the same shape whose evaluation never
 * terminates, so any read that touches it runs out of a {@link
 * no.sikt.graphitron.model.boot.ReadBudget.Bounded} budget rather than answering.
 *
 * <p>This is how a case about the out-of-budget arm provokes one <em>through the real read path</em>:
 * a production query is left exactly as it is and the relation underneath it stops terminating, so
 * the case exercises the same statement the consumer issues. The alternative, a budget small enough
 * that an ordinary query overruns it, would be a wall-clock threshold, and a threshold small enough
 * to be reliable on one machine is a flake on another.
 *
 * <p>The non-termination is structural rather than slow: a recursive term whose frontier never
 * empties, which the fact model records as the shape that hung the store with no diagnostic. It
 * cannot pass by being fast, and it needs no fixture scale, the relation's own rows being what feeds
 * the join.
 *
 * <p>Reads of every <em>other</em> relation are untouched, including the renamed original, so a case
 * can assert that one surface lost its answer while the session kept working.
 *
 * <p>Test-only, and deliberately one-way: the swap is DDL against a fixture's private store, which
 * has to die with the case, so a case that installs this owns the store it installs it into. Not
 * usable inside {@link SeededStore#withSeededStore(java.util.function.Consumer)}, whose store the
 * thread keeps and reuses: the rename would leave a later case reading a non-terminating view.
 * {@link ThreadConfinedStore} refuses rather than hangs, the clear still naming a relation that is
 * now a view and H2 declining to truncate one, but the thread's store is spent either way. Take one
 * from {@link FactStores} and close it.
 */
public final class RunawayRelation {

    private RunawayRelation() {}

    /** Suffix the original table is renamed to, readable by a case that wants the real rows. */
    public static final String ORIGINAL_SUFFIX = "_before_runaway";

    /** The view name the gate is created under, one per store. */
    private static final String GATE = "runaway_gate";

    /**
     * Makes every read of {@code relation} non-terminating, keeping the original rows under
     * {@code relation + }{@link #ORIGINAL_SUFFIX}.
     *
     * <p>The relation must hold at least one row: the gate is cross-joined, so an empty relation
     * still answers empty without ever evaluating it. Every relation a case does this to is one a
     * capture has written.
     *
     * @param dsl the store's own writer surface, this being DDL rather than a read
     * @param relation the unquoted relation name, as the fact schema spells it
     */
    public static void install(DSLContext dsl, String relation) {
        dsl.execute("CREATE VIEW IF NOT EXISTS " + GATE + " AS "
            // BIGINT so the counter cannot overflow into an arithmetic error, which would end the
            // recursion with the wrong failure. The frontier is one row wide and never empty.
            + "WITH RECURSIVE r(n) AS ("
            + "  SELECT CAST(1 AS BIGINT) UNION ALL SELECT n + 1 FROM r"
            + ") SELECT count(*) AS c FROM r");
        dsl.execute("ALTER TABLE " + relation + " RENAME TO " + relation + ORIGINAL_SUFFIX);
        dsl.execute("CREATE VIEW " + relation + " AS "
            + "SELECT t.* FROM " + relation + ORIGINAL_SUFFIX + " t CROSS JOIN " + GATE + " g");
    }
}
