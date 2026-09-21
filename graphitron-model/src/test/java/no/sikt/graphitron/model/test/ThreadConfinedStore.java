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
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.META_STATED_RELATION;

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
     * rather than as growth. Most cases share one store per thread; what boots per case is the
     * classes whose subject is the boot path itself, those that bend the schema they run on, and
     * those whose subject is a store handle rather than a context over one, which the funnel
     * cannot hand them.
     *
     * <p>It bounds {@link FactStores#boots()} rather than {@link #boots()}: the funnel's own count
     * cannot see a boot that does not go through the funnel, and a new path opening a store per
     * case is exactly what would not.
     *
     * <p>One constant over two modules whose counts differ by a factor of seven, so while it sits
     * here it guards this module against nothing; that is the price of keeping the number in one
     * place while the larger module is mid-conversion.
     *
     * <p>Raising it is a deliberate act: recount what boots and why, and if a new class opens a
     * store per case, ask whether it should run on the funnel instead.
     *
     * <p>Raised from 250 to 300 when fixtures started reading the emitted schema off a store
     * instead of off the walk's synthesis. The question the paragraph above asks was asked: this
     * path cannot run on the funnel, because the caller may already be holding a store of its own
     * and borrowing the thread's would clear the rows that fixture captured, which is what
     * happened to five shadow cases before it booted its own. What it can do is not boot twice for
     * one document, and it does not: the derivation is a function of the schema text alone and is
     * memoised on it, which took the count from 288 to 253. So the boots left are one per distinct
     * fixture schema, which is what the module means to pay for, and the headroom is for the
     * schemas it has not written yet rather than for a path nobody looked at.
     */
    private static final int BOOT_BUDGET = 300;

    private final GraphitronModelStore store;

    /** The base tables a clear empties, upper-cased as the catalog spells them. */
    private final List<String> cleared;

    /** Every base table with the row count it held at boot, which is what a clear restores. */
    private final Map<String, Integer> bootState;

    /** One statement counting every base table, built once because the partition is fixed. */
    private final String census;

    private boolean inUse;

    /**
     * Bumped every time this store is cleared, so a fixture can tell whether the borrow it is
     * holding is still its own. Reclaiming a leaked borrow is what keeps one missed close from
     * failing unrelated cases, but it would silently empty a store a fixture still believes in;
     * this is what turns that into a fixture saying so.
     */
    private long generation;

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

    /**
     * The same borrow as {@link #run}, taken and given back by hand rather than around a body, for
     * a fixture whose own shape is acquire-and-close. {@code CapturedStore} hands its caller an
     * {@link AutoCloseable} and cannot wrap the caller's block in a callback. The store rather than
     * its {@code DSLContext}, because a fixture that borrows also mints readers off it.
     *
     * <p><b>A borrow nobody gave back is reclaimed, not refused, and the clear is why.</b> Every
     * borrow begins by putting the store back into its booted state, so a case inheriting one that
     * another case forgot inherits nothing with it. Refusing was tried and is worse than the leak:
     * the next unrelated case on that thread failed, so the failure landed nowhere near its cause
     * and one missed close took 121 cases down with it.
     *
     * <p>Still refused is a borrow taken while a {@link #run} body is executing, where the clear is
     * the damage rather than the repair: it empties the rows that body is asserting on.
     * {@code inUse} is true only for the span of such a body, which makes it an exact test for that
     * and nothing else.
     *
     * <p>What a borrow cannot survive is a case that changes the schema. A clear puts rows back and
     * cannot put a relation back, so a case that drops a table, or demotes a materialized target to
     * a view as the read-cost instrument does, leaves the thread's store a different shape for
     * every case after it. Such a case owns its store; {@code CapturedStore.ownStore} is that arm,
     * and the rule is the one the funnel already had for the gate classes that issue DDL.
     */
    static GraphitronModelStore borrow() {
        ThreadConfinedStore held = HOLDER.get();
        if (held.inUse) {
            throw new IllegalStateException("a seeded-store body is running on this thread, and a"
                + " borrow taken inside it would clear that body's rows out from under it; a case"
                + " wanting a second store should reach FactStores directly, and one wanting a"
                + " second graph should seed it with SeededStore.seedGraph inside the body it has");
        }
        verifyBootBudget();
        held.clear();
        return held.store;
    }

    /** Which borrow the calling thread is on, for a fixture checking that its own is still live. */
    static long generation() {
        return HOLDER.get().generation;
    }

    /**
     * Gives back what {@link #borrow} took. Idempotent, and not load-bearing: the next borrow
     * clears regardless, so this says the fixture is done rather than keeping it correct.
     */
    static void release() {
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
        Set<String> stated = statedRelations(dsl);
        List<String> cleared = baseTables.stream()
            .filter(relation -> clearable(relation, stated)).toList();
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
     *
     * <p>So is every relation the schema states the rows of, and those are read rather than spelled:
     * {@code meta_stated_relation} names exactly them, so a stated relation added later is excluded
     * by having been declared rather than by someone remembering this method. The two exclusions
     * beside it stay spelled because neither is a stated relation: the compatibility stamp is
     * written at boot, and the registry family holds one resident whose rows a boot-time routine
     * derives.
     */
    private static boolean clearable(String relation, Set<String> stated) {
        return !relation.startsWith("META_") && !relation.equals("STORE_STAMP")
            && !stated.contains(relation);
    }

    /** The relations this schema supplies the rows of, upper-cased as the catalog names them. */
    private static Set<String> statedRelations(DSLContext dsl) {
        return dsl.select(META_STATED_RELATION.RELATION_NAME)
            .from(META_STATED_RELATION)
            .fetch(0, String.class).stream()
            .map(name -> name.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
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
        generation++;
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
        // The statistics go back with the rows. H2 keeps per-column selectivity across a truncate,
        // and it sets some of its own once a table passes two thousand changes, so a store cleared
        // of rows alone is cold in what it holds and warm in what it knows. What that leaks is a
        // plan rather than a row, so the guard above cannot see it: a case reading its own rows
        // gets the right answer through a plan the previous case's volume chose, and the day it
        // matters it matters as an order-dependent failure in a case that never touched the store
        // that poisoned it. StoreStatistics.reset is what a created store carries, the partition
        // declaration included, which is the state this borrow is claiming to hand over.
        StoreStatistics.reset(dsl);
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
