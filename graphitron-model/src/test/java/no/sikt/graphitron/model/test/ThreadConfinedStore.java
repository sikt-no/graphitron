package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One store per test thread, kept for the thread's lifetime, with its rows cleared between bodies.
 * What {@link SeededStore#withSeededStore(Consumer)} runs on, and the reason a case in this module
 * no longer pays for a schema boot.
 *
 * <p>The trade this makes is a boot for a clear. Applying the fact schema costs a few hundred
 * milliseconds under this module's four-way parallelism, and truncating every table a case can
 * write to costs under a millisecond, so a suite of hundreds of cases that each booted a private
 * store spent most of its time on DDL that produced the same empty schema every time. A thread
 * boots once, and every body after the first meets a store the clear has put back into its
 * freshly booted state.
 *
 * <p>Confined per thread rather than shared across them. One store behind a lock would serialize
 * every fixture write in the module and trade a measured cost for an unmeasured one, where a store
 * per thread keeps cases as independent as they were when each one opened its own. Under the
 * module's four-way class-level parallelism that is four boots per surefire JVM. The store is
 * never closed: an in-memory H2 dies with the JVM, so there is nothing to leak past the fork, and
 * a thread that finished its last class has nothing left to hand its store to.
 *
 * <p>The correctness this rests on is that a clear really does reproduce a booted store, which is
 * a property of the schema rather than of a list kept here. A booted store holds rows in exactly
 * three relations: the {@code meta_} registry family, whose rows the DDL seeds and the bootstrap
 * derives, and {@code store_stamp}. Every other base table is empty. So the partition is derived
 * from {@code INFORMATION_SCHEMA} when the thread boots, the clear takes the empty half, and
 * {@link #verifyBootState} asserts at boot that the half really was empty, which is the claim the
 * whole mechanism stands on and the one thing a schema edit could break.
 *
 * <p>Deriving that partition once is what puts DDL out of bounds for a funnel case. A case that
 * creates, drops or renames a relation changes the schema the partition describes, for itself and
 * for every later case on the thread. Nothing in the funnel does, and the classes in this module
 * whose subject is the schema's shape boot their own stores; a case that needs DDL should take one
 * from {@link FactStores} and close it, {@link RunawayRelation} in particular. It is a trap rather
 * than a hole: the clear names a relation the DDL has turned into something else, and H2 refuses.
 *
 * <p>Re-deriving the materialization dependency edges is not part of a clear.
 * {@code MaterializeDependencies.populate} derives them from the registry and from the stored view
 * definitions in {@code INFORMATION_SCHEMA}, reading no fact relation, so no row a case writes and
 * no row a clear removes can invalidate them. {@link SeededStore#derive} is the other derivation
 * and it does depend on fact rows, which is why a case still calls it per case; that has not
 * changed and is not a clear's concern.
 */
final class ThreadConfinedStore {

    /**
     * A thread's store, booted on first use. Deliberately never removed: removing it is the one
     * edit that would break the invariant {@link #boots()} and {@link #bootingThreads()} pin, and
     * there is nothing to reclaim before the fork exits.
     */
    private static final ThreadLocal<ThreadConfinedStore> HOLDER =
        ThreadLocal.withInitial(ThreadConfinedStore::boot);

    private static final AtomicLong BOOTS = new AtomicLong();
    private static final Set<Long> BOOTING_THREADS = ConcurrentHashMap.newKeySet();

    /**
     * The most stores this module may open in one JVM before the funnel treats it as a regression
     * rather than as growth. About thirty-five are expected: one per thread that runs a funnel case,
     * the twenty-odd per-case boots of the classes whose subject is the boot path rather than their
     * setup, {@link CandidateCutSet}'s five, which are per case because realising a candidate
     * rewrites the register one way and no clear puts it back, and
     * {@code WrittenStatementCoverageTest}'s two, which bend the schema they run on (referential
     * integrity off, check constraints dropped) and so cannot share a store with anything, and
     * {@code LentStoreTest}'s three, whose subject is a store one caller opens and another
     * captures into: two of them are one home opened twice, the second open being warm, which is
     * the only state the ownership check reads. Orders of magnitude under the case count, which is
     * the number this exists to keep the module away from.
     *
     * <p>It bounds {@link FactStores#boots()} rather than {@link #boots()}, which is the whole
     * point of having two counters: the funnel's own count cannot see a boot that does not go
     * through the funnel, and a new path that opens a store per case is exactly what would not.
     *
     * <p>Stated here rather than on {@code FactStores} even though it is that counter's budget,
     * because the harness is reached from four modules and only this one has adopted the funnel.
     * The others boot per case by design and in the hundreds, so a number enforced down there would
     * be this module's claim imposed on theirs. When one of them adopts a funnel it states its own.
     *
     * <p>Raising it is a deliberate act: recount what boots and why, and if the answer is that a
     * new class opens a store per case, the question is whether it should run on the funnel instead.
     * The funnel is not always the answer: it hands a body a {@link org.jooq.DSLContext}, so a case
     * whose subject is a store <em>handle</em> (one lent to something that captures into it, or one
     * home reopened to meet its own previous rows) cannot ask it for what it needs, which is the
     * reason the last three boots above are counted here rather than routed away.
     */
    private static final int BOOT_BUDGET = 75;

    private final GraphitronModelStore store;

    /** The base tables a clear empties, upper-cased as the catalog spells them. */
    private final List<String> cleared;

    /** Every base table with the row count it held at boot, which is what a clear restores. */
    private final Map<String, Integer> bootState;

    /** One statement counting every base table, built once because the partition is fixed. */
    private final String census;

    private boolean inUse;

    private ThreadConfinedStore(GraphitronModelStore store, List<String> cleared,
                                Map<String, Integer> bootState, String census) {
        this.store = store;
        this.cleared = cleared;
        this.bootState = bootState;
        this.census = census;
    }

    /**
     * Clears the calling thread's store, booting it if this is the thread's first body, and runs
     * {@code body} against it.
     *
     * <p>Clears before rather than after, so a body always starts from a store this call put into
     * its booted state rather than from one a previous body promised to leave that way. A body
     * that throws therefore costs nothing beyond its own failure.
     *
     * @throws IllegalStateException if called from inside another body on the same thread, or if a
     *         row survived the clear
     */
    static void run(Consumer<DSLContext> body) {
        ThreadConfinedStore held = HOLDER.get();
        if (held.inUse) {
            throw new IllegalStateException("a seeded-store body is already running on this thread,"
                + " and the inner call would clear the outer body's rows out from under it while"
                + " handing back the same store; a case wanting a second store should reach"
                + " FactStores directly, and one wanting a second graph should seed it with"
                + " SeededStore.seedGraph inside the body it already has");
        }
        verifyBootBudget();
        held.inUse = true;
        try {
            held.clear();
            body.accept(held.store.dsl());
        } finally {
            held.inUse = false;
        }
    }

    /** The budget {@link FactStores#boots()} is held to, so a test need not restate the number. */
    static int bootBudget() {
        return BOOT_BUDGET;
    }

    /**
     * How many stores this module's funnel has booted, which the mechanism claims is one per thread
     * that ever ran a case through it.
     */
    static long boots() {
        return BOOTS.get();
    }

    /**
     * How many distinct threads have booted a store through the funnel. Equal to {@link #boots()}
     * exactly while no thread has booted twice, which is the confinement invariant itself.
     */
    static int bootingThreads() {
        return BOOTING_THREADS.size();
    }

    /**
     * Fails the calling case if the module has opened more stores than {@link #BOOT_BUDGET} allows.
     *
     * <p>Checked on every funnel call rather than in a test method, because the count is monotonic
     * and a test reads it wherever the class scheduler happened to reach that class: under
     * concurrent classes in an unspecified order, an assertion that ran early sees a fraction of the
     * run and passes on a suite that ended far over budget. A funnel call happens hundreds of times
     * across the whole run, so checking here samples the total continuously and names the case that
     * was running when the budget went. What it cannot see is a boot after the run's last funnel
     * call, which is at most one class's worth and is the price of not needing a listener to hold
     * the check.
     */
    private static void verifyBootBudget() {
        long boots = FactStores.boots();
        if (boots > BOOT_BUDGET) {
            throw new IllegalStateException(("this module has opened %d stores in one JVM, past its"
                + " budget of %d. Almost every case is meant to run on the store its thread booted"
                + " once, through SeededStore.withSeededStore, so a count this high means either a"
                + " new path opens a store per case or a class that boots per case has grown. Route"
                + " it through the funnel, or raise ThreadConfinedStore.BOOT_BUDGET deliberately"
                + " once the recount says the boots are ones the module means to pay for.")
                .formatted(boots, BOOT_BUDGET));
        }
    }

    private static ThreadConfinedStore boot() {
        GraphitronModelStore store = FactStores.inMemory();
        DSLContext dsl = store.dsl();
        List<String> baseTables = baseTables(dsl);
        List<String> cleared = baseTables.stream().filter(ThreadConfinedStore::clearable).toList();
        String census = census(baseTables);
        Map<String, Integer> bootState = counts(dsl, census);
        verifyBootState(cleared, bootState);
        BOOTS.incrementAndGet();
        BOOTING_THREADS.add(Thread.currentThread().threadId());
        return new ThreadConfinedStore(store, cleared, bootState, census);
    }

    /**
     * Whether a clear empties {@code relation}. The registry family and the compatibility stamp are
     * the store's own rows rather than a case's, and a boot is what puts them there.
     */
    private static boolean clearable(String relation) {
        return !relation.startsWith("META_") && !relation.equals("STORE_STAMP");
    }

    /**
     * Empties every clearable table and asserts the store is back in its booted state.
     *
     * <p>{@code TRUNCATE} rather than {@code DELETE}, and the flag around it is what makes that
     * legal rather than merely unchecked. H2 refuses to truncate a table any foreign key
     * references, which is nearly all of these, but it asks whether referential integrity is on
     * before it declines. That is the difference between a clear costing under a millisecond and
     * costing a delete per table in foreign-key order. The flag goes back on in a {@code finally}
     * because a case asserting that a foreign key rejects a row has to keep failing when it should:
     * leaving it off could not corrupt anything, the flag being per database and each thread's store
     * being its own, but it would silently retire a whole family of assertions, and it should break
     * here rather than there.
     */
    private void clear() {
        DSLContext dsl = store.dsl();
        try {
            dsl.execute("SET REFERENTIAL_INTEGRITY FALSE");
            for (String relation : cleared) {
                dsl.execute("TRUNCATE TABLE \"" + relation + "\"");
            }
        } finally {
            dsl.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        verifyCleared(counts(dsl, census));
    }

    /**
     * The leak guard, and the one part of this mechanism that could otherwise fail silently: a case
     * passing because a previous case's row survived is worse than a slow suite, and it would not
     * announce itself.
     *
     * <p>Over every base table rather than over the clear's own list, which would assert a
     * tautology. After a truncate of exactly those tables, "those tables are empty" is entailed by
     * the truncate; the leak worth catching is a row surviving in a table the clear does not reach,
     * and the exclusion in {@link #clearable} is exactly what decides which tables those are. A
     * guard scoped to that exclusion's output cannot see the exclusion being wrong. Nothing in the
     * funnel writes to an excluded table today, so this is about the guard that stays behind: a
     * {@code meta_} table added later that a seeded case writes into passes a shared-list guard
     * silently and fails this one.
     *
     * <p>One statement rather than a probe per table, because it is the same assertion at a
     * fraction of the round trips.
     */
    private void verifyCleared(Map<String, Integer> now) {
        for (Map.Entry<String, Integer> entry : bootState.entrySet()) {
            int booted = entry.getValue();
            int found = now.getOrDefault(entry.getKey(), -1);
            if (found != booted) {
                throw new IllegalStateException(("the clear did not put the store back into its"
                    + " booted state: %s holds %d rows where a booted store holds %d. A row that"
                    + " survives a clear is a row the next case sees, so this fails here rather"
                    + " than as that case passing for the wrong reason. Either the case that just"
                    + " ran wrote to a relation the clear excludes, or the schema gained a relation"
                    + " the exclusion in ThreadConfinedStore.clearable now catches by accident.")
                    .formatted(entry.getKey(), found, booted));
            }
        }
    }

    /**
     * Asserts the claim a clear rests on: every table it empties was empty on a freshly booted
     * store, so emptying them is the same thing as booting. Fails at boot rather than as a case
     * mysteriously missing rows the DDL put there.
     */
    private static void verifyBootState(List<String> cleared, Map<String, Integer> bootState) {
        List<String> populated = cleared.stream().filter(relation -> bootState.get(relation) > 0).toList();
        if (!populated.isEmpty()) {
            throw new IllegalStateException(("a freshly booted store already holds rows in %s, which"
                + " the clear between cases empties, so clearing would hand the next case a store"
                + " no boot could produce. Either the relation belongs with the registry family the"
                + " exclusion in ThreadConfinedStore.clearable names, or whatever seeds it belongs"
                + " in a case rather than in the schema.").formatted(populated));
        }
    }

    /** Every base table in the store's schema, upper-cased as the catalog spells them. */
    private static List<String> baseTables(DSLContext dsl) {
        return dsl.fetch("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'PUBLIC' AND table_type = 'BASE TABLE' ORDER BY table_name")
            .getValues(0, String.class);
    }

    /** One statement returning a row count per base table, in one round trip. */
    private static String census(List<String> baseTables) {
        List<String> terms = new ArrayList<>(baseTables.size());
        for (String relation : baseTables) {
            terms.add("SELECT '%s' AS relation, count(*) AS row_count FROM \"%s\""
                .formatted(relation, relation));
        }
        return String.join(" UNION ALL ", terms);
    }

    private static Map<String, Integer> counts(DSLContext dsl, String census) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Record row : dsl.fetch(census)) {
            counts.put(row.get(0, String.class).toUpperCase(Locale.ROOT), row.get(1, Integer.class));
        }
        return counts;
    }
}
