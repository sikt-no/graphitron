package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.StoreUse;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fact store for a test to assert against, in the two shapes the store has: private and
 * in-memory, or persisted under a home on disk. The bottom of every fact-store harness in the
 * reactor, and the one place a test opens a store.
 *
 * <p>Named entry points rather than one flagged factory, because the two shapes are not a
 * configuration of each other. An in-memory store dies with the handle and can never be reopened,
 * which is what makes it the right substrate for a case whose subject is rows; a file-backed one
 * survives its handle, reports where it landed, and is what a case about warm start or about a
 * reader on another connection needs. A boolean at the call site would read as a detail rather
 * than as the choice it is.
 *
 * <p>What this level does <em>not</em> do is put rows in the store. That is deliberate, and it is
 * what lets every level above stand on this one: a case fills the store by running a capture,
 * through {@link CapturedStore} or the gatherers themselves, and above this module a case whose
 * subject is the generator fills it by running a real build through that module's own harness. A store handle that
 * arrived with rows already in it would serve none of them.
 *
 * <p>This harness owns no lifetime. Every entry point hands back a {@link GraphitronModelStore}
 * the caller closes, normally through try-with-resources, so a case that holds two stores at once
 * or reopens one directory is expressible here rather than having to reach past the harness.
 *
 * <p>It counts what it opens, and that count is the only instrument that sees every boot in a run.
 * A schema boot is the most expensive thing a fact-store test can do, so this module runs almost
 * every case on a store {@link ThreadConfinedStore} booted once per thread and clears between
 * bodies; {@link #boots()} is what notices a path that boots per case appearing beside it. What
 * counts as too many boots is each module's own claim, since the harness is reached from four: a
 * module states its budget as {@value #BOOT_BUDGET_PROPERTY} in its surefire configuration, and
 * every boot is checked against it.
 */
public final class FactStores {

    private static final AtomicLong BOOTS = new AtomicLong();

    /**
     * The system property a module states its boot budget in: the most stores one test JVM may
     * open before a boot counts as a regression rather than as growth. Unset, nothing is bounded.
     */
    public static final String BOOT_BUDGET_PROPERTY = "graphitron.test.bootBudget";

    private static final AtomicLong CAPTURES = new AtomicLong();

    /** The captures that were the first through their connection, which plan every statement cold. */
    private static final AtomicLong COLD_CAPTURES = new AtomicLong();

    /**
     * The connections a capture has run through. H2 keeps a statement cache per session, so the
     * first capture through a connection prepares every statement it runs, and a later one through
     * the same connection reuses them. Held weakly, so a closed store's connection is not kept.
     */
    private static final java.util.Map<java.sql.Connection, Boolean> CAPTURED_THROUGH =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * The counts per thread, {@code [boots, captures, cold captures]}, for {@link StoreCostExtension}'s
     * attribution. Only the owning thread writes its own array, so it needs no lock.
     */
    private static final ThreadLocal<long[]> ON_THIS_THREAD = ThreadLocal.withInitial(() -> new long[3]);

    private FactStores() {}

    /**
     * A private in-memory store, empty, with the fact schema created in it. The default: nothing
     * on disk to clean up, nothing shared with a concurrent test, and the whole store gone when
     * the handle closes.
     */
    public static GraphitronModelStore inMemory() {
        return inMemory(StoreUse.ONE_CAPTURE);
    }

    /**
     * A private in-memory store as {@link #inMemory()} hands back, for the use {@code use} names. A
     * case that captures again and again on one store says {@link StoreUse#LONG_LIVED}; a test JVM
     * holding many stores at once should not, since each one that fills its statement cache costs
     * about 30 MB.
     */
    public static GraphitronModelStore inMemory(StoreUse use) {
        countBoot();
        return GraphitronModelStore.open(use);
    }

    /**
     * A store persisted under {@code home}, which the store stamps a compatibility segment onto
     * before keeping its file there. What an in-memory store cannot answer for: that a second open
     * of the same home meets the first one's rows, that the handle reports the directory it landed
     * in, and that a reader mints onto the file rather than onto a guessed one.
     *
     * <p>Hand it a fresh temporary directory per case. The store spares the live stamp it opens and
     * releases only older stamped directories nothing holds, so a shared home carries a previous
     * case's rows into the next one's assertions.
     */
    public static GraphitronModelStore fileBacked(Path home) {
        return fileBacked(home, StoreUse.ONE_CAPTURE);
    }

    /** A persisted store as {@link #fileBacked(Path)} hands back, for the use {@code use} names. */
    public static GraphitronModelStore fileBacked(Path home, StoreUse use) {
        countBoot();
        return GraphitronModelStore.openAt(home, use);
    }

    /**
     * The fact schema's own text, as the store boots it.
     *
     * <p>Here rather than at a reader, for the reason every other entry point is here: this is the
     * one place the test side names the store, and a gate over the authored DDL has to read the
     * same resource the boot reads or the two would answer different questions about what the
     * schema says. A reader of this text is asking about the schema as authored, which is a
     * question no booted catalog can answer: the engine keeps a view's expanded definition and has
     * already lost the spelling by the time a column has a type.
     *
     * <p>Each stage's rule files are appended as the view each once was, {@code CREATE VIEW <rule>
     * AS <text>;}: a rule is its stage's own and no part of the model, but H2 parses and plans its
     * text as it did the view's, so a gate over how a statement is written reads the rules too.
     */
    public static String schemaText() {
        String ddl;
        try (var in = GraphitronModelStore.class
                .getResourceAsStream(GraphitronModelStore.DDL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the fact schema is not on the test classpath at "
                    + GraphitronModelStore.DDL_RESOURCE + "; a reader of it would scan nothing");
            }
            ddl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        var text = new StringBuilder(ddl);
        for (var file : StageRuleFiles.all()) {
            text.append("\n\nCREATE VIEW ").append(file.rule()).append(" AS\n").append(file.text())
                .append(";\n");
        }
        return text.toString();
    }

    /**
     * How many stores this harness has opened so far in this JVM, across every thread and every
     * entry point. Monotonic, so a reader that wants a run's total reads it at the end of the run;
     * a module holding itself to a budget states it as {@value #BOOT_BUDGET_PROPERTY}.
     */
    public static long boots() {
        return BOOTS.get();
    }

    /**
     * How many captures {@link CapturedStore} has run so far in this JVM, across every thread and
     * every arm: each one is a {@link no.sikt.graphitron.model.run.ModelCapture} pass, derivation
     * gatherer included, and is the price a boot is small beside. Monotonic, like {@link #boots()}.
     *
     * <p>What it cannot see is a capture that does not pass through {@link CapturedStore}: the
     * generator's own {@code GraphitronStore.captured} path, which a built store and a rendered
     * outcome block run, is production code and counts nothing. A module reading this as its
     * capture total reads a floor over those.
     */
    public static long captures() {
        return CAPTURES.get();
    }

    /** {@link #boots()} restricted to the calling thread. */
    static long bootsOnThisThread() {
        return ON_THIS_THREAD.get()[0];
    }

    /** {@link #captures()} restricted to the calling thread. */
    static long capturesOnThisThread() {
        return ON_THIS_THREAD.get()[1];
    }

    /** {@link #captures()} that were the first through their connection: a fresh session's. */
    public static long coldCaptures() {
        return COLD_CAPTURES.get();
    }

    /** {@link #coldCaptures()} restricted to the calling thread. */
    static long coldCapturesOnThisThread() {
        return ON_THIS_THREAD.get()[2];
    }

    /**
     * Called by {@link CapturedStore} where every capture it runs passes, with the context it
     * captures through, so a capture into a fresh session is told from one into a pooled store.
     */
    static void countCapture(org.jooq.DSLContext dsl) {
        CAPTURES.incrementAndGet();
        ON_THIS_THREAD.get()[1]++;
        var connection = dsl.connectionResult(c -> c);
        if (CAPTURED_THROUGH.put(connection, Boolean.TRUE) == null) {
            COLD_CAPTURES.incrementAndGet();
            ON_THIS_THREAD.get()[2]++;
        }
    }

    /** The budget the running module states, if it states one. */
    public static OptionalLong bootBudget() {
        String stated = System.getProperty(BOOT_BUDGET_PROPERTY);
        return stated == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(stated));
    }

    /**
     * Counts a boot about to happen, and refuses it past the module's budget. Checked here rather
     * than in a test method, because the count is monotonic and a test reads it wherever the class
     * scheduler happened to reach that class: the boot that goes over fails, in the case that asked
     * for it, before the store opens.
     */
    private static void countBoot() {
        long boots = BOOTS.incrementAndGet();
        ON_THIS_THREAD.get()[0]++;
        var budget = bootBudget();
        if (budget.isPresent() && boots > budget.getAsLong()) {
            throw new IllegalStateException(("this module has opened %d stores in one JVM, past its"
                + " budget of %d. A case reading a fact document spends no boot of its own, so a"
                + " count this high means a new path opens a store per case or a class that boots"
                + " per case has grown. Read through FactGraphs, or raise %s in the module's"
                + " surefire configuration deliberately once the recount says the boots are ones"
                + " the module means to pay for.")
                .formatted(boots, budget.getAsLong(), BOOT_BUDGET_PROPERTY));
        }
    }

    /**
     * One in-memory store for a whole test class, as a JUnit extension.
     *
     * <p>The third lifetime, beside the two above, and it is a lifetime rather than a convenience:
     * a class whose cases each open their own store pays the fact schema's two thousand statements
     * per case, which is what makes a case-per-claim test class expensive enough that an author
     * starts merging claims to keep it fast. Declared as an extension rather than as a
     * {@code @BeforeAll} field so no test spells the store type, and so the close cannot be
     * forgotten:
     *
     * <pre>{@code
     * @RegisterExtension
     * static final FactStores.ClassStore STORE = FactStores.perClass();
     *
     * @Test void aClaim() {
     *     var store = STORE.handle();
     *     ...
     * }
     * }</pre>
     *
     * <p>Not the per-thread funnel, and the reason is the surface rather than the lifetime:
     * {@link ThreadConfinedStore#run(java.util.function.Consumer)} hands a body a
     * {@link org.jooq.DSLContext}, deliberately, and a case that needs the store <em>handle</em>
     * (the connection, or something the handle mints) has nothing to ask it for. Widening the funnel
     * to hand out the handle would give every case on it the ability to close the thread's store.
     * These boots are counted in {@link #boots()} like any other and land under the module's budget.
     *
     * <p>Only for a class whose cases do not interfere. The store arrives empty and stays whatever
     * the cases leave it, so a class that seeds rows and counts them wants a store per case, from
     * {@link #inMemory()}, or the funnel, which clears between bodies.
     */
    public static ClassStore perClass() {
        return new ClassStore();
    }

    /**
     * The handle behind {@link #perClass()}. Owns the store's lifetime, opening it before the
     * class's first case and closing it after the last, so a case borrows the handle and never
     * closes it.
     */
    public static final class ClassStore implements BeforeAllCallback, AfterAllCallback {

        private GraphitronModelStore store;

        private ClassStore() {}

        /** The store, open for the duration of the class. Never closed by a caller. */
        public GraphitronModelStore handle() {
            if (store == null) {
                throw new IllegalStateException(
                    "the class store is not open; declare it @RegisterExtension on a static field");
            }
            return store;
        }

        @Override
        public void beforeAll(ExtensionContext context) {
            store = inMemory();
        }

        @Override
        public void afterAll(ExtensionContext context) {
            if (store != null) {
                store.close();
                store = null;
            }
        }
    }
}
